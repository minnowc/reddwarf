package com.sun.gi.comm.users.client.impl;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.security.auth.callback.Callback;

import com.sun.gi.comm.discovery.DiscoveredGame;
import com.sun.gi.comm.discovery.DiscoveredUserManager;
import com.sun.gi.comm.discovery.Discoverer;
import com.sun.gi.comm.users.client.ClientAlreadyConnectedException;
import com.sun.gi.comm.users.client.ClientConnectionManager;
import com.sun.gi.comm.users.client.ClientConnectionManagerListener;
import com.sun.gi.comm.users.client.UserManagerClient;
import com.sun.gi.comm.users.client.UserManagerClientListener;
import com.sun.gi.comm.users.client.UserManagerPolicy;
import com.sun.gi.utils.types.BYTEARRAY;

public class ClientConnectionManagerImpl implements ClientConnectionManager,
		UserManagerClientListener {
	Discoverer discoverer;

	UserManagerPolicy policy;

	private UserManagerClient umanager;

	private Class umanagerClass;

	private String gameName;

	private byte[] reconnectionKey = null;

	private ClientConnectionManagerListener listener;

	private byte[] myID;

	private boolean reconnecting = false;

	private boolean connected = false;

	private Map<BYTEARRAY, ClientChannelImpl> channelMap = new HashMap<BYTEARRAY, ClientChannelImpl>();

	private long keyTimeout = 0;

	// used to do multiple conn attempts
	private long connAttempts;

	private long connAttemptCounter;

	private long connWaitMS;

	private boolean exiting;

	public ClientConnectionManagerImpl(String gameName, Discoverer disco) {
		this(gameName, disco, new DefaultUserManagerPolicy());
	}

	public ClientConnectionManagerImpl(String gameName, Discoverer disco,
			UserManagerPolicy policy) {
		discoverer = disco;
		this.policy = policy;
		this.gameName = gameName;
	}

	public void setListener(ClientConnectionManagerListener l) {
		listener = l;
	}

	public String[] getUserManagerClassNames() {
		DiscoveredGame game = discoverGame(gameName);
		if (game == null) {
			return null;
		}
		DiscoveredUserManager[] umgrs = game.getUserManagers();
		if (umgrs == null) {
			return null;
		}
		Set<String> names = new HashSet<String>();
		for (int j = 0; j < umgrs.length; j++) {
			names.add(umgrs[j].getClientClass());
		}
		String[] outnames = new String[names.size()];
		return (String[]) names.toArray(outnames);

	}

	/**
	 * discoverGame
	 * 
	 * @param gameID
	 *            int
	 * @return DiscoveredGame
	 */
	private DiscoveredGame discoverGame(String gameName) {
		DiscoveredGame[] games = discoverer.games();
		for (int i = 0; i < games.length; i++) {
			if (games[i].getName().equals(gameName)) {
				return games[i];
			}
		}
		System.out.println("Discovery Error: No games discovered!");
		return null;
	}

	public boolean connect(String userManagerClassName)
			throws ClientAlreadyConnectedException {
		int attempts = 10;
		String attstr = System.getProperty("sgs.clientconnmgr.connattempts");
		if (attstr != null) {
			attempts = Integer.parseInt(attstr);
		}
		long sleepTime = 100;
		String sleepstr = System.getProperty("sgs.clientconnmgr.connwait");
		if (sleepstr != null) {
			sleepTime = Long.parseLong(sleepstr);
		}
		return connect(userManagerClassName, attempts, sleepTime);
	}

	public boolean connect(String userManagerClassName, int attempts,
			long sleepTime) throws ClientAlreadyConnectedException {
		if (connected) {
			throw new ClientAlreadyConnectedException("bad attempt to connect "
					+ "when already connected.");
		}
		try {
			umanagerClass = Class.forName(userManagerClassName);
			umanager = (UserManagerClient) umanagerClass.newInstance();
		} catch (Exception ex) {
			ex.printStackTrace();
			return false;
		}
		connAttempts = attempts;
		connWaitMS = sleepTime;
		connAttemptCounter = 0;
		reconnecting = false;
		return connect(umanager);
	}

	private boolean connect(UserManagerClient umanager) {
		connAttemptCounter++;
		exiting = false;
		DiscoveredGame game = discoverGame(gameName);
		DiscoveredUserManager choice = policy.choose(game, umanager.getClass()
				.getName());

		return umanager.connect(choice, this);

	}

	public void disconnect() {
		exiting = true;
		umanager.logout();
	}

	public void sendValidationResponse(Callback[] cbs) {
		umanager.validationDataResponse(cbs);
	}

	public void openChannel(String channelName) {
		umanager.joinChannel(channelName);
	}

	public void sendToServer(ByteBuffer buff, boolean reliable) {
		umanager.sendToServer(buff, reliable);

	}

	// callbacks from UserClientManagerListener
	/**
	 * dataReceived
	 * 
	 * @param from
	 *            ByteBuffer
	 * @param data
	 *            ByteBuffer
	 */
	public void dataReceived(byte[] chanID, byte[] from, ByteBuffer data,
			boolean reliable) {
		ClientChannelImpl chan = channelMap.get(new BYTEARRAY(chanID));
		chan.dataReceived(from, data, reliable);
	}

	/**
	 * disconnected
	 */
	public void disconnected() {
		if (connected == false) { // not yet connected
			if (connAttemptCounter < connAttempts) { // try again
				try {
					Thread.sleep(connWaitMS);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				connect(umanager);
			} else {
				listener.disconnected();
			}
		} else { // lost connection
			if ((!exiting) && (keyTimeout > System.currentTimeMillis())) { 
				// valid reconn key
				listener.failOverInProgress();
				reconnecting = true;
				connect(umanager);
			} else { // we cant fail over
				listener.disconnected();
			}
		}
	}

	/**
	 * connected
	 * 
	 * 
	 */
	public void connected() {
		connected = true;
		if (!reconnecting || (keyTimeout < System.currentTimeMillis())) {
			umanager.login();
		} else {
			umanager.reconnectLogin(myID, reconnectionKey);
		}
	}

	/**
	 * validationDataRequest
	 * 
	 * 
	 */
	public void validationDataRequest(Callback[] cbs) {
		listener.validationRequest(cbs);

	}

	/**
	 * loginAccepted
	 * 
	 * @param userID
	 *            ByteBuffer
	 */
	public void loginAccepted(byte[] userID) {
		myID = new byte[userID.length];
		System.arraycopy(userID, 0, myID, 0, myID.length);
		listener.connected(myID);
	}

	/**
	 * loginRejected
	 * 
	 * 
	 */
	public void loginRejected(String message) {
		listener.connectionRefused(message);
	}

	/**
	 * userAdded
	 * 
	 * @param userID
	 *            ByteBuffer
	 */
	public void userAdded(byte[] userID) {
		listener.userJoined(userID);
	}

	/**
	 * userDropped
	 * 
	 * @param userID
	 *            ByteBuffer
	 */
	public void userDropped(byte[] userID) {
		listener.userLeft(userID);
	}

	/**
	 * newConnectionKeyIssued
	 * 
	 * @param key
	 *            long
	 */
	public void newConnectionKeyIssued(byte[] key, long ttl) {
		reconnectionKey = new byte[key.length];
		System.arraycopy(key, 0, reconnectionKey, 0, key.length);
		keyTimeout = System.currentTimeMillis() + (ttl * 1000);
	}

	public void joinedChannel(String name, byte[] channelID) {
		ClientChannelImpl chan = new ClientChannelImpl(this, name, channelID);
		channelMap.put(new BYTEARRAY(channelID), chan);
		listener.joinedChannel(chan);
	}

	public void leftChannel(byte[] channelID) {
		ClientChannelImpl chan = channelMap.remove(new BYTEARRAY(channelID));
		chan.channelClosed();

	}

	public void userJoinedChannel(byte[] channelID, byte[] userID) {
		ClientChannelImpl chan = channelMap.get(new BYTEARRAY(channelID));
		chan.userJoined(userID);
	}

	public void userLeftChannel(byte[] channelID, byte[] userID) {
		ClientChannelImpl chan = channelMap.get(new BYTEARRAY(channelID));
		chan.userLeft(userID);

	}

	public void recvdData(byte[] chanID, byte[] from, ByteBuffer data,
			boolean reliable) {
		ClientChannelImpl chan = channelMap.get(new BYTEARRAY(chanID));
		chan.dataReceived(from, data, reliable);
	}

	/*
	 * The below are all package private and intended just for use by
	 * ClientChannelImpl
	 */

	void sendUnicastData(byte[] chanID, byte[] to, ByteBuffer data,
			boolean reliable) {
		umanager.sendUnicastMsg(chanID, to, data, reliable);

	}

	void sendMulticastData(byte[] chanID, byte[][] to, ByteBuffer data,
			boolean reliable) {
		umanager.sendMulticastMsg(chanID, to, data, reliable);

	}

	void sendBroadcastData(byte[] chanID, ByteBuffer data, boolean reliable) {
		umanager.sendBroadcastMsg(chanID, data, reliable);

	}

}
