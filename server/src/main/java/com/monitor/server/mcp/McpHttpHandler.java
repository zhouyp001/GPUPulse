package com.monitor.server.mcp;

import com.monitor.server.ServerConfig;
import com.monitor.server.ServerRegistry;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import io.netty.util.concurrent.ScheduledFuture;
import io.netty.util.ReferenceCountUtil;

import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * MCP（Model Context Protocol）HTTP 面：{@code /mcp} 路径，Streamable HTTP 传输。
 * <ul>
 *   <li>POST：JSON-RPC 2.0 请求 → JSON 响应；通知 → 202 Accepted 空体</li>
 *   <li>GET：SSE（text/event-stream），周期 keep-alive，无会话状态</li>
 * </ul>
 * 只处理去掉查询串后精确等于 {@code mcpPath} 的请求；其余请求 {@code ctx.fireChannelRead} 放行，
 * 由后续处理器接管（引用计数随之转移），对既有路由零影响。
 *
 * <p>可配共享 token：{@code server.mcp.token} 非空时要求每个请求带 {@code Authorization: Bearer <token>}。</p>
 *
 * <p>注意：本处理器继承 {@link ChannelInboundHandlerAdapter} 而非 {@link io.netty.channel.SimpleChannelInboundHandler}，
 * 因为放行分支要把引用所有权交给下游 —— 只有匹配 {@code /mcp} 时本类才负责释放。</p>
 */
public final class McpHttpHandler extends ChannelInboundHandlerAdapter {
    private static final Logger LOG = Logger.getLogger(McpHttpHandler.class.getName());
    private static final long KEEPALIVE_SECONDS = 15;

    private final String path;
    private final String token;
    private final McpServer server;

    public McpHttpHandler(ServerRegistry registry, ServerConfig cfg) {
        this.path = cfg.mcpPath();
        this.token = cfg.mcpToken();
        this.server = new McpServer(new McpCapabilities(registry));
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof FullHttpRequest)) {
            ctx.fireChannelRead(msg);
            return;
        }
        FullHttpRequest req = (FullHttpRequest) msg;
        if (!isMcpPath(req.uri())) {
            // 非 MCP 请求：交给后续处理器（引用跟着走，不在此释放）
            ctx.fireChannelRead(msg);
            return;
        }
        try {
            if (!authorized(req)) {
                sendText(ctx, HttpResponseStatus.UNAUTHORIZED, "401 Unauthorized");
                return;
            }
            if (req.method() == io.netty.handler.codec.http.HttpMethod.POST) {
                handlePost(ctx, req);
            } else if (req.method() == io.netty.handler.codec.http.HttpMethod.GET) {
                handleSse(ctx, req);
            } else {
                sendText(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "405 Method Not Allowed");
            }
        } finally {
            // MCP 请求已被本类消费：释放引用，避免泄漏
            ReferenceCountUtil.release(req);
        }
    }

    private boolean isMcpPath(String uri) {
        String pathOnly = uri;
        int q = pathOnly.indexOf('?');
        if (q >= 0) {
            pathOnly = pathOnly.substring(0, q);
        }
        return pathOnly.equals(path);
    }

    private boolean authorized(FullHttpRequest req) {
        if (token == null || token.isEmpty()) {
            return true;
        }
        String auth = req.headers().get(HttpHeaderNames.AUTHORIZATION);
        return auth != null && auth.startsWith("Bearer ")
                && token.equals(auth.substring("Bearer ".length()).trim());
    }

    private void handlePost(ChannelHandlerContext ctx, FullHttpRequest req) {
        try {
            String body = req.content().toString(CharsetUtil.UTF_8);
            String response = server.handle(body);
            if (response == null) {
                // 通知：202 Accepted 空体
                DefaultFullHttpResponse resp = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.ACCEPTED, Unpooled.EMPTY_BUFFER);
                HttpUtil.setKeepAlive(resp, HttpUtil.isKeepAlive(req));
                ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
            } else {
                sendJson(ctx, req, HttpResponseStatus.OK, response);
            }
        } catch (Exception e) {
            LOG.warning("MCP POST error: " + e.getMessage());
            sendText(ctx, HttpResponseStatus.BAD_REQUEST, "400 Bad Request");
        }
    }

    /** SSE 流：先发响应头 + 一条连接注释，再周期发送 keep-alive，直到连接关闭。 */
    private void handleSse(ChannelHandlerContext ctx, FullHttpRequest req) {
        DefaultHttpResponse resp = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream; charset=utf-8");
        resp.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
        resp.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
        resp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        ctx.writeAndFlush(resp);
        ctx.writeAndFlush(new DefaultHttpContent(Unpooled.copiedBuffer(": connected\n\n", CharsetUtil.UTF_8)));
        ScheduledFuture<?> keeper = ctx.executor().scheduleAtFixedRate(() -> {
            if (ctx.channel().isActive()) {
                ctx.writeAndFlush(new DefaultHttpContent(
                        Unpooled.copiedBuffer(": keep-alive\n\n", CharsetUtil.UTF_8)));
            }
        }, KEEPALIVE_SECONDS, KEEPALIVE_SECONDS, TimeUnit.SECONDS);
        ctx.channel().closeFuture().addListener(f -> keeper.cancel(false));
    }

    private void sendJson(ChannelHandlerContext ctx, FullHttpRequest req, HttpResponseStatus status, String body) {
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, Unpooled.copiedBuffer(body, CharsetUtil.UTF_8));
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
        resp.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, resp.content().readableBytes());
        HttpUtil.setKeepAlive(resp, HttpUtil.isKeepAlive(req));
        ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
    }

    private void sendText(ChannelHandlerContext ctx, HttpResponseStatus status, String body) {
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, Unpooled.copiedBuffer(body, CharsetUtil.UTF_8));
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
        resp.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, resp.content().readableBytes());
        ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
    }
}
