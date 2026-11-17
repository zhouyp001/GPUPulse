package com.monitor.server.ws;

import com.monitor.server.ServerRegistry;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;

import java.util.logging.Logger;

/**
 * 面板 WebSocket 处理器：连接建立后先推送一次全量快照，之后仅被服务器推送。
 * 入站文本帧当前仅支持 "snapshot" 指令（预留），其余忽略。
 */
public class PanelWebSocketHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private static final Logger LOG = Logger.getLogger(PanelWebSocketHandler.class.getName());

    private final ServerRegistry registry;
    private final ChannelGroup panels;

    public PanelWebSocketHandler(ServerRegistry registry, ChannelGroup panels) {
        this.registry = registry;
        this.panels = panels;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        panels.add(ctx.channel());
        LOG.info("面板连接: " + ctx.channel().remoteAddress());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        panels.remove(ctx.channel());
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt == WebSocketServerProtocolHandler.ServerHandshakeStateEvent.HANDSHAKE_COMPLETE) {
            // 握手完成即推一次全量快照，让面板立即出数
            ctx.writeAndFlush(new TextWebSocketFrame(registry.snapshot().toString()));
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame msg) {
        String text = msg.text();
        if (text != null && text.equals("snapshot")) {
            ctx.writeAndFlush(new TextWebSocketFrame(registry.snapshot().toString()));
        }
    }
}
