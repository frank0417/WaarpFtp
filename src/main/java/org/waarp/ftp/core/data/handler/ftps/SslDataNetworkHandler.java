/**
   This file is part of Waarp Project.

   Copyright 2009, Frederic Bregier, and individual contributors by the @author
   tags. See the COPYRIGHT.txt in the distribution for a full listing of
   individual contributors.

   All Waarp Project is free software: you can redistribute it and/or 
   modify it under the terms of the GNU General Public License as published 
   by the Free Software Foundation, either version 3 of the License, or
   (at your option) any later version.

   Waarp is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
   GNU General Public License for more details.

   You should have received a copy of the GNU General Public License
   along with Waarp .  If not, see <http://www.gnu.org/licenses/>.
 */
package org.waarp.ftp.core.data.handler.ftps;

import java.util.concurrent.ConcurrentHashMap;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.handler.ssl.SslHandler;
import org.waarp.common.future.WaarpFuture;
import org.waarp.common.logging.WaarpInternalLogger;
import org.waarp.common.logging.WaarpInternalLoggerFactory;
import org.waarp.ftp.core.config.FtpConfiguration;
import org.waarp.ftp.core.config.FtpInternalConfiguration;
import org.waarp.ftp.core.data.handler.DataBusinessHandler;
import org.waarp.ftp.core.data.handler.DataNetworkHandler;

/**
 * @author "Frederic Bregier"
 *
 */
public class SslDataNetworkHandler extends DataNetworkHandler {
	/**
	 * Internal Logger
	 */
	private static final WaarpInternalLogger logger = WaarpInternalLoggerFactory
			.getLogger(SslDataNetworkHandler.class);
	/**
	 * Waiter for SSL handshake is finished
	 */
	private static final ConcurrentHashMap<Integer, WaarpFuture> waitForSsl = new ConcurrentHashMap<Integer, WaarpFuture>();

	/**
	 * @param configuration
	 * @param handler
	 * @param active
	 */
	public SslDataNetworkHandler(FtpConfiguration configuration, DataBusinessHandler handler,
			boolean active) {
		super(configuration, handler, active);
	}
	/**
	 * Remover from SSL HashMap
	 */
	private static final ChannelFutureListener remover = new ChannelFutureListener() {
		public void operationComplete(
				ChannelFuture future) {
			logger.debug("SSL remover");
			waitForSsl
					.remove(future
							.getChannel()
							.getId());
		}
	};

	/**
	 * Add the Channel as SSL handshake is over
	 * 
	 * @param channel
	 */
	private static void addSslConnectedChannel(Channel channel) {
		WaarpFuture futureSSL = new WaarpFuture(true);
		waitForSsl.put(channel.getId(), futureSSL);
		channel.getCloseFuture().addListener(remover);
	}

	/**
	 * Set the future of SSL handshake to status
	 * 
	 * @param channel
	 * @param status
	 */
	private static void setStatusSslConnectedChannel(Channel channel, boolean status) {
		WaarpFuture futureSSL = waitForSsl.get(channel.getId());
		if (futureSSL != null) {
			if (status) {
				futureSSL.setSuccess();
			} else {
				futureSSL.cancel();
			}
		}
	}

	/**
	 * 
	 * @param channel
	 * @return True if the SSL handshake is over and OK, else False
	 */
	public boolean isSslConnectedChannel(Channel channel) {
		WaarpFuture futureSSL = waitForSsl.get(channel.getId());
		if (futureSSL == null) {
			for (int i = 0; i < FtpInternalConfiguration.RETRYNB; i++) {
				futureSSL = waitForSsl.get(channel.getId());
				if (futureSSL != null)
					break;
				try {
					Thread.sleep(FtpInternalConfiguration.RETRYINMS);
				} catch (InterruptedException e) {
				}
			}
		}
		if (futureSSL == null) {
			logger.debug("No wait For SSL found");
			return false;
		} else {
			try {
				futureSSL.await(getFtpSession().getConfiguration().TIMEOUTCON);
			} catch (InterruptedException e) {
			}
			if (futureSSL.isDone()) {
				logger.debug("Wait For SSL: " + futureSSL.isSuccess());
				return futureSSL.isSuccess();
			}
			logger.error("Out of time for wait For SSL");
			return false;
		}
	}

	@Override
	public void channelOpen(ChannelHandlerContext ctx, ChannelStateEvent e)
			throws Exception {
		Channel channel = e.getChannel();
		logger.debug("Add channel to ssl");
		addSslConnectedChannel(channel);
		super.channelOpen(ctx, e);
	}

	/**
	 * To be extended to inform of an error to SNMP support
	 * @param error1
	 * @param error2
	 */
	protected void callForSnmp(String error1, String error2) {
		// ignore
	}

	@Override
	public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) {
		// Get the SslHandler in the current pipeline.
		// We added it in NetworkSslServerPipelineFactory.
		final SslHandler sslHandler = ctx.getPipeline().get(SslHandler.class);
		if (sslHandler != null) {
			// Get the SslHandler and begin handshake ASAP.
			// Get notified when SSL handshake is done.
			ChannelFuture handshakeFuture;
			handshakeFuture = sslHandler.handshake();
			handshakeFuture.addListener(new ChannelFutureListener() {
				public void operationComplete(ChannelFuture future)
						throws Exception {
					logger.debug("Handshake: " + future.isSuccess(), future.getCause());
					if (future.isSuccess()) {
						setStatusSslConnectedChannel(future.getChannel(), true);
					} else {
						String error2 = future.getCause() != null ?
								future.getCause().getMessage() : "During Handshake";
						callForSnmp("SSL Connection Error", error2);
						setStatusSslConnectedChannel(future.getChannel(), false);
						future.getChannel().close();
					}
				}
			});
		} else {
			logger.error("SSL Not found");
		}
		super.channelConnected(ctx, e);
	}

}