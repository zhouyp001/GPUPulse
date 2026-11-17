package com.monitor.server.http;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monitor.model.MetricFrame;
import com.monitor.server.ServerRegistry;
import com.monitor.util.Json;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.CharsetUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpMethod.POST;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;

/**
 * HTTP 业务处理器：
 * <ul>
 *   <li>POST /report       接收 Agent 上报的指标帧，更新注册表并广播给面板</li>
 *   <li>GET  /api/servers  返回全量快照（调试 / 非 WebSocket 客户端用）</li>
 *   <li>其他 GET           从 web 目录提供静态文件（面板前端）</li>
 * </ul>
 */
public final class HttpApiHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final Map<String, String> MIME = new HashMap<>();

    static {
        MIME.put("html", "text/html; charset=utf-8");
        MIME.put("htm", "text/html; charset=utf-8");
        MIME.put("css", "text/css; charset=utf-8");
        MIME.put("js", "application/javascript; charset=utf-8");
        MIME.put("json", "application/json; charset=utf-8");
        MIME.put("png", "image/png");
        MIME.put("jpg", "image/jpeg");
        MIME.put("jpeg", "image/jpeg");
        MIME.put("svg", "image/svg+xml");
        MIME.put("ico", "image/x-icon");
        MIME.put("txt", "text/plain; charset=utf-8");
    }

    private final ServerRegistry registry;
    private final ChannelGroup panels;
    private final Path webRoot;

    public HttpApiHandler(ServerRegistry registry, ChannelGroup panels, Path webRoot) {
        this.registry = registry;
        this.panels = panels;
        this.webRoot = webRoot.toAbsolutePath().normalize();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
        String uri = req.uri();
        try {
            if (req.method() == POST && uri.equals("/report")) {
                handleReport(ctx, req);
            } else if (req.method() == GET && uri.equals("/api/servers")) {
                sendJson(ctx, OK, registry.snapshot());
            } else if (req.method() == POST && uri.equals("/api/servers/clear-offline")) {
                handleClearOffline(ctx);
            } else if (req.method() == GET) {
                serveStatic(ctx, req, uri);
            } else {
                sendText(ctx, NOT_FOUND, "404 Not Found");
            }
        } finally {
            // SimpleChannelInboundHandler 会自动释放本次请求
        }
    }

    private void handleReport(ChannelHandlerContext ctx, FullHttpRequest req) {
        try {
            MetricFrame frame = Json.fromJson(
                    req.content().toString(StandardCharsets.UTF_8), MetricFrame.class);
            registry.update(frame);
            panels.writeAndFlush(new TextWebSocketFrame(registry.updateMessage(frame.server).toString()));
            sendText(ctx, OK, "ok");
        } catch (Exception e) {
            sendText(ctx, BAD_REQUEST, "bad frame: " + e.getMessage());
        }
    }

    /** 清空所有离线记录，并向面板广播移除消息，返回被移除数量。 */
    private void handleClearOffline(ChannelHandlerContext ctx) {
        List<String> removed = registry.removeOffline();
        if (!removed.isEmpty()) {
            ObjectNode msg = Json.MAPPER.createObjectNode();
            msg.put("type", "remove");
            ArrayNode arr = msg.putArray("servers");
            removed.forEach(arr::add);
            panels.writeAndFlush(new TextWebSocketFrame(msg.toString()));
        }
        ObjectNode res = Json.MAPPER.createObjectNode();
        res.put("removed", removed.size());
        sendJson(ctx, OK, res);
    }

    private void serveStatic(ChannelHandlerContext ctx, FullHttpRequest req, String uri) {
        String path = uri;
        if (path.equals("/") || path.isEmpty()) {
            path = "/index.html";
        }
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        String rel = path.startsWith("/") ? path.substring(1) : path;
        if (rel.contains("..")) {
            sendText(ctx, FORBIDDEN, "403 Forbidden");
            return;
        }
        Path file = webRoot.resolve(rel).normalize();
        if (!file.startsWith(webRoot) || !Files.isRegularFile(file)) {
            sendText(ctx, NOT_FOUND, "404 Not Found");
            return;
        }
        try {
            byte[] content = Files.readAllBytes(file);
            String ext = extensionOf(rel);
            String mime = MIME.getOrDefault(ext, "application/octet-stream");
            FullHttpResponse resp = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, OK, Unpooled.wrappedBuffer(content));
            resp.headers().set(HttpHeaderNames.CONTENT_TYPE, mime);
            resp.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.length);
            HttpUtil.setKeepAlive(resp, HttpUtil.isKeepAlive(req));
            ctx.writeAndFlush(resp);
        } catch (IOException e) {
            sendText(ctx, INTERNAL_SERVER_ERROR, "500 read error");
        }
    }

    private static String extensionOf(String rel) {
        int dot = rel.lastIndexOf('.');
        int slash = rel.lastIndexOf('/');
        return (dot > slash) ? rel.substring(dot + 1) : "";
    }

    private void sendText(ChannelHandlerContext ctx, io.netty.handler.codec.http.HttpResponseStatus status, String body) {
        FullHttpResponse resp = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, Unpooled.copiedBuffer(body, CharsetUtil.UTF_8));
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
        resp.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, resp.content().readableBytes());
        ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
    }

    private void sendJson(ChannelHandlerContext ctx, io.netty.handler.codec.http.HttpResponseStatus status, ObjectNode node) {
        FullHttpResponse resp = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, Unpooled.copiedBuffer(node.toString(), CharsetUtil.UTF_8));
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
        resp.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, resp.content().readableBytes());
        ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
    }
}
