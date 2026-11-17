package com.monitor.server;

import com.monitor.server.http.HttpApiHandler;
import com.monitor.server.ws.PanelWebSocketHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.concurrent.GlobalEventExecutor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * 中央服务器入口（纯 Netty，无 Spring）：
 * <ol>
 *   <li>接收 Agent 的 HTTP POST 指标帧（/report）</li>
 *   <li>内存注册表维护各服务器最新帧与在线状态</li>
 *   <li>WebSocket 向浏览器面板广播实时更新（/ws）</li>
 *   <li>托管 web/ 静态前端（/）</li>
 * </ol>
 */
public class ServerMain {
    private static final Logger LOG = Logger.getLogger(ServerMain.class.getName());

    public static void main(String[] args) throws Exception {
        ServerConfig cfg = new ServerConfig();
        ServerRegistry registry = new ServerRegistry();
        ChannelGroup panels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

        Path webRoot = resolveWebRoot(cfg.webDir());
        LOG.info("面板静态目录: " + webRoot);

        NioEventLoopGroup boss = new NioEventLoopGroup(1);
        NioEventLoopGroup worker = new NioEventLoopGroup();
        try {
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(boss, worker)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline()
                                    .addLast(new HttpServerCodec())
                                    .addLast(new HttpObjectAggregator(1024 * 1024))
                                    .addLast(new WebSocketServerProtocolHandler("/ws"))
                                    .addLast(new HttpApiHandler(registry, panels, webRoot))
                                    .addLast(new PanelWebSocketHandler(registry, panels));
                        }
                    });

            Channel serverChannel = bootstrap.bind(cfg.port()).sync().channel();
            LOG.info("服务器监控面板已启动: http://localhost:" + cfg.port() + "/");

            // 定时扫描离线服务器并向面板广播状态变化
            boss.scheduleAtFixedRate(() -> {
                long now = System.currentTimeMillis();
                List<String> offline = registry.markOffline(now, cfg.offlineMs());
                for (String id : offline) {
                    panels.writeAndFlush(new TextWebSocketFrame(registry.statusMessage(id).toString()));
                    LOG.info("服务器离线: " + id);
                }
            }, cfg.syncIntervalMs(), cfg.syncIntervalMs(), TimeUnit.MILLISECONDS);

            serverChannel.closeFuture().sync();
        } finally {
            boss.shutdownGracefully().sync();
            worker.shutdownGracefully().sync();
        }
    }

    /** web 目录解析：优先当前工作目录，其次上级目录，便于不同运行位置都能找到 web/。 */
    private static Path resolveWebRoot(String dir) {
        Path direct = Paths.get(dir).toAbsolutePath().normalize();
        if (Files.isDirectory(direct)) {
            return direct;
        }
        Path parent = Paths.get("..", dir).toAbsolutePath().normalize();
        if (Files.isDirectory(parent)) {
            return parent;
        }
        LOG.warning("静态目录不存在：" + direct + "，静态资源访问将返回 404");
        return direct;
    }
}
