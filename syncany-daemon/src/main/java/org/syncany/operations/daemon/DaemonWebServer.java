/*
 * Syncany, www.syncany.org
 * Copyright (C) 2011-2013 Philipp C. Heckel <philipp.heckel@gmail.com> 
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.syncany.operations.daemon;

import static io.undertow.Handlers.path;
import static io.undertow.Handlers.resource;
import static io.undertow.Handlers.websocket;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.resource.ClassPathResourceManager;
import io.undertow.util.Headers;
import io.undertow.websockets.WebSocketConnectionCallback;
import io.undertow.websockets.core.AbstractReceiveListener;
import io.undertow.websockets.core.BufferedTextMessage;
import io.undertow.websockets.core.StreamSourceFrameChannel;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.WebSockets;
import io.undertow.websockets.spi.WebSocketHttpExchange;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.simpleframework.xml.Serializer;
import org.simpleframework.xml.core.Persister;
import org.syncany.operations.daemon.messages.BadRequestResponse;
import org.syncany.operations.daemon.messages.BinaryResponse;
import org.syncany.operations.daemon.messages.Request;
import org.syncany.operations.daemon.messages.RequestFactory;
import org.syncany.operations.daemon.messages.Response;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.eventbus.Subscribe;

public class DaemonWebServer {
	private static final Logger logger = Logger.getLogger(DaemonWebServer.class.getSimpleName());
	private static final String WEBSOCKET_ALLOWED_ORIGIN_HEADER = "localhost";
	private static final int DEFAULT_PORT = 8080;

	private Undertow webServer;
	private Serializer serializer;
	private DaemonEventBus eventBus;
	private Cache<Integer, WebSocketChannel> requestIdCache;
	private List<WebSocketChannel> clientChannels;

	public DaemonWebServer() {
		this.serializer = new Persister();
		this.clientChannels = new ArrayList<WebSocketChannel>();

		initCache();
		initEventBus();
		initServer();
	}

	public void start() throws ServiceAlreadyStartedException {
		webServer.start();
	}

	public void stop() {
		try {
			webServer.stop();
		}
		catch (Exception e) {
			logger.log(Level.SEVERE, "Could not stop websocket server.", e);
		}
	}

	private void initCache() {
		requestIdCache = CacheBuilder.newBuilder().maximumSize(10000).concurrencyLevel(2).expireAfterAccess(1, TimeUnit.MINUTES).build();
	}

	private void initEventBus() {
		eventBus = DaemonEventBus.getInstance();
		eventBus.register(this);
	}

	private void initServer() {
		webServer = Undertow
				.builder()
				.addHttpListener(DEFAULT_PORT, "localhost")
				.setHandler(
						path().addPrefixPath("/api/ws", websocket(new InternalWebSocketHandler()))
								.addPrefixPath("/api/rest", new InternalRestHandler())
								.addPrefixPath(
										"/",
										resource(new ClassPathResourceManager(DaemonWebServer.class.getClassLoader(), "org/syncany/web/simple/"))
												.addWelcomeFiles("index.html").setDirectoryListingEnabled(true))).build();
	}

	private void handleMessage(WebSocketChannel clientSocket, String message) {
		logger.log(Level.INFO, "Web socket message received: " + message);

		try {
			Request request = RequestFactory.createRequest(message);

			requestIdCache.put(request.getId(), clientSocket);
			eventBus.post(request);
		}
		catch (Exception e) {
			logger.log(Level.WARNING, "Invalid request received; cannot serialize to Request.", e);
			eventBus.post(new BadRequestResponse(-1, "Invalid request."));
		}
	}

	private void sendBroadcast(String message) {
		synchronized (clientChannels) {
			for (WebSocketChannel clientChannel : clientChannels) {
				sendTo(clientChannel, message);
			}
		}
	}

	private void sendTo(WebSocketChannel clientChannel, String message) {
		logger.log(Level.INFO, "Sending message to " + clientChannel + ": " + message);
		WebSockets.sendText(message, clientChannel, null);
	}

	@Subscribe
	public void onResponse(Response response) {
		try {
			// Serialize response
			StringWriter responseWriter = new StringWriter();
			serializer.write(response, responseWriter);

			String responseMessage = responseWriter.toString();

			// Send to one or many receivers
			boolean responseWithoutRequest = response.getRequestId() == null || response.getRequestId() <= 0;

			if (responseWithoutRequest) {
				sendBroadcast(responseMessage);
			}
			else {
				WebSocketChannel responseToClientSocket = requestIdCache.asMap().get(response.getRequestId());

				if (responseToClientSocket != null) {
					sendTo(responseToClientSocket, responseMessage);
				}
				else {
					logger.log(Level.WARNING, "Cannot send message, because request ID in response is unknown or timed out." + responseMessage);
				}
			}
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Subscribe
	public void onResponse(final BinaryResponse response) {
		WebSocketChannel responseToClientChannel = requestIdCache.asMap().get(response.getRequestId());

		if (responseToClientChannel != null) {
			logger.log(Level.INFO, "Sending binary frame to " + responseToClientChannel + "...");

			try {
				WebSockets.sendBinaryBlocking(response.getData(), responseToClientChannel);
				responseToClientChannel.resumeReceives();
			}
			catch (IOException e) {
				logger.log(Level.WARNING, "Cannot send BINARY message, sending frame failed.", e);
			}
		}
		else {
			logger.log(Level.WARNING, "Cannot send BINARY message, because request ID in response is unknown or timed out.");
		}
	}

	private class InternalWebSocketHandler implements WebSocketConnectionCallback {
		@Override
		public void onConnect(WebSocketHttpExchange exchange, WebSocketChannel channel) {
			channel.getReceiveSetter().set(new AbstractReceiveListener() {
				@Override
				protected void onFullTextMessage(WebSocketChannel clientChannel, BufferedTextMessage message) {
					handleMessage(clientChannel, message.getData());
				}

				@Override
				protected void onError(WebSocketChannel webSocketChannel, Throwable error) {
					logger.log(Level.INFO, "Server error : " + error.toString());
				}

				@Override
				protected void onClose(WebSocketChannel clientChannel, StreamSourceFrameChannel streamSourceChannel) throws IOException {
					logger.log(Level.INFO, clientChannel.toString() + " disconnected");

					synchronized (clientChannels) {
						clientChannels.remove(clientChannel);
					}
				}
			});

			synchronized (clientChannels) {
				clientChannels.add(channel);
			}

			channel.resumeReceives();
		}
	}

	private class InternalRestHandler implements HttpHandler {
		@Override
		public void handleRequest(final HttpServerExchange exchange) throws Exception {
			exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/xml");
			exchange.getResponseSender().send("<xml>Hello World</xml>");
		}
	}
}