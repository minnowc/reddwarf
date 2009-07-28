/*
 * Copyright 2007-2009 Sun Microsystems, Inc.
 *
 * This file is part of Project Darkstar Server.
 *
 * Project Darkstar Server is free software: you can redistribute it
 * and/or modify it under the terms of the GNU General Public License
 * version 2 as published by the Free Software Foundation and
 * distributed hereunder to you.
 *
 * Project Darkstar Server is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.sun.sgs.impl.protocol.simple;

import com.sun.sgs.app.Delivery;
import com.sun.sgs.auth.Identity;
import com.sun.sgs.impl.sharedutil.HexDumper;
import com.sun.sgs.impl.sharedutil.LoggerWrapper;
import com.sun.sgs.impl.sharedutil.MessageBuffer;
import com.sun.sgs.impl.util.AbstractCompletionFuture;
import com.sun.sgs.impl.util.AbstractKernelRunnable;
import com.sun.sgs.nio.channels.AsynchronousByteChannel;
import com.sun.sgs.nio.channels.ClosedAsynchronousChannelException;
import com.sun.sgs.nio.channels.CompletionHandler;
import com.sun.sgs.nio.channels.IoFuture;
import com.sun.sgs.nio.channels.ReadPendingException;
import com.sun.sgs.protocol.LoginFailureException;
import com.sun.sgs.protocol.LoginRedirectException;
import com.sun.sgs.protocol.ProtocolDescriptor;
import com.sun.sgs.protocol.ProtocolListener;
import com.sun.sgs.protocol.RelocateFailureException;
import com.sun.sgs.protocol.RequestFailureException;
import com.sun.sgs.protocol.RequestFailureException.FailureReason;
import com.sun.sgs.protocol.RequestCompletionHandler;
import com.sun.sgs.protocol.SessionProtocol;
import com.sun.sgs.protocol.SessionProtocolHandler;
import com.sun.sgs.protocol.simple.SimpleSgsProtocol;
import com.sun.sgs.service.Node;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implements the protocol specified in {@code SimpleSgsProtocol}.  The
 * implementation uses a wrapper channel, {@link AsynchronousMessageChannel},
 * that reads and writes complete messages by framing messages with a 2-byte
 * message length, and masking (and re-issuing) partial I/O operations.  Also
 * enforces a fixed buffer size when reading.
 */
public class SimpleSgsProtocolImpl implements SessionProtocol {
    /** The number of bytes used to represent the message length. */
    private static final int PREFIX_LENGTH = 2;

    /** The logger for this class. */
    private static final LoggerWrapper staticLogger = new LoggerWrapper(
	Logger.getLogger(SimpleSgsProtocolImpl.class.getName()));

    /** The default reason string returned for login failure. */
    private static final String DEFAULT_LOGIN_FAILED_REASON = "login refused";

    /** The default reason string returned for relocation failure. */
    private static final String DEFAULT_RELOCATE_FAILED_REASON =
	"relocation refused";

    /** The default length of the reconnect key, in bytes.
     * TBD: the reconnection key length should be configurable.
     */
    private static final int DEFAULT_RECONNECT_KEY_LENGTH = 16;

    /** A random number generator for reconnect keys. */
    private static final SecureRandom random = new SecureRandom();

    /**
     * The underlying channel (possibly another layer of abstraction,
     * e.g. compression, retransmission...).
     */
    private final AsynchronousMessageChannel asyncMsgChannel;

    /** The protocol handler. */
    protected volatile SessionProtocolHandler protocolHandler;

    /** This protocol's acceptor. */
    protected final SimpleSgsProtocolAcceptor acceptor;

    /** The logger for this instance. */
    private final LoggerWrapper logger;
    
   /** The protocol listener. */
    private final ProtocolListener listener;

    /** The identity. */
    private volatile Identity identity;

    /** The reconnect key. */
    protected final byte[] reconnectKey;

    /** The completion handler for reading from the I/O channel. */
    private volatile ReadHandler readHandler = new ConnectedReadHandler();

    /** The completion handler for writing to the I/O channel. */
    private volatile WriteHandler writeHandler = new ConnectedWriteHandler();

    /** A lock for {@code loginHandled}, {@code messageQueue},
     * {@suspendCompletionFuture}, and {@code relocationInfo}  fields. */
    private Object lock = new Object();

    /** Indicates whether the client's login ack has been sent. */
    private boolean loginHandled = false;

    /** Messages enqueued to be sent after a login ack is sent. */
    private List<ByteBuffer> messageQueue = new ArrayList<ByteBuffer>();

    /** The set of supported delivery requirements. */
    protected final Set<Delivery> deliverySet = new HashSet<Delivery>();

    /** The completion future if suspending messages is in progress, or
     * null. */
    private SuspendMessagesCompletionFuture suspendCompletionFuture = null;
    
    /** The session's relocation information, if this session is relocating to
     * another node. */
    private RelocationInfo relocationInfo = null;

    /**
     * Creates a new instance of this class.
     *
     * @param	listener a protocol listener
     * @param	acceptor the {@code SimpleSgsProtocol} acceptor
     * @param	byteChannel a byte channel for the underlying connection
     * @param	readBufferSize the read buffer size
     */
    SimpleSgsProtocolImpl(ProtocolListener listener,
                          SimpleSgsProtocolAcceptor acceptor,
                          AsynchronousByteChannel byteChannel,
                          int readBufferSize)
    {
	this(listener, acceptor, byteChannel, readBufferSize, staticLogger);
	/*
	 * TBD: It might be a good idea to implement high- and low-water marks
	 * for the buffers, so they don't go into hysteresis when they get
	 * full. -JM
	 */
	scheduleReadOnReadHandler();
    }
    
    /**
     *
     * The subclass should invoke {@link #scheduleReadOnReadHandler} after
     * constructing the instance to commence reading.
     *
     * @param	listener a protocol listener
     * @param	acceptor the {@code SimpleSgsProtocol} acceptor
     * @param	byteChannel a byte channel for the underlying connection
     * @param	readBufferSize the read buffer size
     * @param	logger a logger for this instance
     */
    protected  SimpleSgsProtocolImpl(ProtocolListener listener,
				     SimpleSgsProtocolAcceptor acceptor,
				     AsynchronousByteChannel byteChannel,
				     int readBufferSize,
				     LoggerWrapper logger)
    {
	// The read buffer size lower bound is enforced by the protocol acceptor
	assert readBufferSize >= PREFIX_LENGTH;
	this.asyncMsgChannel =
	    new AsynchronousMessageChannel(byteChannel, readBufferSize);
	this.listener = listener;
	this.acceptor = acceptor;
	this.logger = logger;
	this.reconnectKey = getNextReconnectKey();
	deliverySet.add(Delivery.RELIABLE);
    }
    
    /* -- Implement SessionProtocol -- */

    /** {@inheritDoc} */
    public Set<Delivery> getDeliveries() {
	return Collections.unmodifiableSet(deliverySet);
    }
    
    /** {@inheritDoc} */
    public int getMaxMessageLength() {
        // largest message size is max for channel messages
        return
	    SimpleSgsProtocol.MAX_MESSAGE_LENGTH -
	    1 -           // Opcode
	    2 -           // channel ID size
	    8;            // (max) channel ID bytes
    }
    
    /** {@inheritDoc} */
    public void sessionMessage(ByteBuffer message, Delivery delivery) {
	checkSuspend();
	int messageLength = 1 + message.remaining();
        assert messageLength <= SimpleSgsProtocol.MAX_MESSAGE_LENGTH;
	ByteBuffer buf = ByteBuffer.wrap(new byte[messageLength]);
	buf.put(SimpleSgsProtocol.SESSION_MESSAGE).
	    put(message).
	    flip();
	writeBuffer(buf, delivery);
    }
    
    /** {@inheritDoc} */
    public void channelJoin(
	String name, BigInteger channelId, Delivery delivery) {
	checkSuspend();
	byte[] channelIdBytes = channelId.toByteArray();
	MessageBuffer buf =
	    new MessageBuffer(1 + MessageBuffer.getSize(name) +
			      channelIdBytes.length);
	buf.putByte(SimpleSgsProtocol.CHANNEL_JOIN).
	    putString(name).
	    putBytes(channelIdBytes);
	writeOrEnqueueIfLoginNotHandled(ByteBuffer.wrap(buf.getBuffer()));
    }

    /** {@inheritDoc} */
    public void channelLeave(BigInteger channelId) {
	checkSuspend();
	byte[] channelIdBytes = channelId.toByteArray();
	ByteBuffer buf =
	    ByteBuffer.allocate(1 + channelIdBytes.length);
	buf.put(SimpleSgsProtocol.CHANNEL_LEAVE).
	    put(channelIdBytes).
	    flip();
	writeOrEnqueueIfLoginNotHandled(buf);
    }

    /***
     * {@inheritDoc}
     *
     * <p>This implementation invokes the protected method {@link
     * #writeBuffer writeBuffer} with the channel protocol message (a
     * {@code ByteBuffer}) and the specified delivery requirement.  A
     * subclass can override the {@code writeBuffer} method if it supports
     * other delivery guarantees and can make use of alternate transports
     * for those other delivery requirements.
     */
    public void channelMessage(BigInteger channelId,
                               ByteBuffer message,
                               Delivery delivery)
    {
	checkSuspend();
	byte[] channelIdBytes = channelId.toByteArray();
	int messageLength = 3 + channelIdBytes.length + message.remaining();
        assert messageLength <= SimpleSgsProtocol.MAX_MESSAGE_LENGTH;
	ByteBuffer buf =
	    ByteBuffer.allocate(messageLength);
	buf.put(SimpleSgsProtocol.CHANNEL_MESSAGE).
	    putShort((short) channelIdBytes.length).
	    put(channelIdBytes).
	    put(message).
	    flip();
	writeBuffer(buf, delivery);
    }

    /**
     * Writes the specified buffer, satisfying the specified delivery
     * requirement.
     *
     * <p>This implementation writes the buffer reliably, because this
     * protocol only supports reliable delivery.
     *
     * <p>A subclass can override the {@code writeBuffer} method if it
     * supports other delivery guarantees and can make use of alternate
     * transports for those other delivery requirements.
     *
     * @param	buf a byte buffer containing a protocol message
     * @param	delivery a delivery requirement
     */
    protected void writeBuffer(ByteBuffer buf, Delivery delivery) {
	writeOrEnqueueIfLoginNotHandled(buf);
    }

    /** {@inheritDoc} */
    public void suspend(RequestCompletionHandler<Void> completionHandler) {
	synchronized (lock) {
	    if (suspendCompletionFuture != null) {
		throw new IllegalStateException(
		    "already suspending messages");
	    }
	    suspendCompletionFuture =
		new SuspendMessagesCompletionFuture(completionHandler);
	}
	ByteBuffer buf = ByteBuffer.allocate(1);
	buf.put(SimpleSgsProtocol.SUSPEND_MESSAGES).
	    flip();
	writeToWriteHandler(buf);
	flushMessageQueue();
    }

    /** {@inheritDoc} */
    public void resume() {
	synchronized (lock) {
	    if (suspendCompletionFuture != null) {
		suspendCompletionFuture = null;
		ByteBuffer buf = ByteBuffer.allocate(1);
		buf.put(SimpleSgsProtocol.RESUME_MESSAGES).
		    flip();
		writeToWriteHandler(buf);
		flushMessageQueue();
	    }
	}
    }

    /** {@inheritDoc} */
    public void relocate(Node newNode,
			 Set<ProtocolDescriptor> descriptors,
			 ByteBuffer relocationKey,
			 RequestCompletionHandler<Void> completionHandler)
    {
	synchronized (lock) {
	    if (relocationInfo != null) {
		throw new IllegalStateException("session already relocating");
	    }
	    if (suspendCompletionFuture == null) {
		suspend(completionHandler);
	    }
	    relocationInfo =
		new RelocationInfo(descriptors, relocationKey);
	}
    }
    
    /** {@inheritDoc} */
    public void disconnect(DisconnectReason reason) throws IOException {
	// TBD: The SimpleSgsProtocol does not yet support sending a
	// message to the client in the case of session termination or
	// preemption, so just close the connection for now.
        close();
    }
    
    /* -- Private methods for sending protocol messages -- */

    /**
     * Notifies the associated client that the previous login attempt was
     * successful.
     */
    protected void loginSuccess() {
	MessageBuffer buf = new MessageBuffer(1 + reconnectKey.length);
	buf.putByte(SimpleSgsProtocol.LOGIN_SUCCESS).
	    putBytes(reconnectKey);
	writeToWriteHandler(ByteBuffer.wrap(buf.getBuffer()));
	flushMessageQueue();
    }

    /**
     * Notifies the associated client that it should redirect its login to
     * the specified {@code node} with the specified protocol {@code
     * descriptors}.
     *
     * @param	node a node to redirect the login
     * @param	descriptors a set of protocol descriptors supported
     *		by {@code node}
     */
    private void loginRedirect(
	Node node, Set<ProtocolDescriptor> descriptors)
    {
        for (ProtocolDescriptor descriptor : descriptors) {
            if (acceptor.getDescriptor().supportsProtocol(descriptor)) {
		byte[] redirectionData =
		    ((SimpleSgsProtocolDescriptor) descriptor).
		        getConnectionData();
		MessageBuffer buf =
		    new MessageBuffer(1 + redirectionData.length);
		buf.putByte(SimpleSgsProtocol.LOGIN_REDIRECT).
		    putBytes(redirectionData);
		writeToWriteHandler(ByteBuffer.wrap(buf.getBuffer()));
		flushMessageQueue();
		acceptor.monitorDisconnection(this);
                return;
            }
        }
        loginFailure("redirect failed", null);
        logger.log(Level.SEVERE,
                   "redirect node {0} does not support a compatable protocol",
                   node);
    }

    /**
     * Notifies the associated client that the previous login attempt was
     * unsuccessful for the specified {@code reason}.  The specified {@code
     * throwable}, if non-{@code null} is an exception that occurred while
     * processing the login request.  The message channel should be careful
     * not to reveal to the associated client sensitive data that may be
     * present in the specified {@code throwable}.
     *
     * @param	reason a reason why the login was unsuccessful
     * @param	throwable an exception that occurred while processing the
     *		login request, or {@code null}
     */
    private void loginFailure(String reason, Throwable ignore) {
	// for now, override specified reason.
	reason = DEFAULT_LOGIN_FAILED_REASON;
        MessageBuffer buf =
	    new MessageBuffer(1 + MessageBuffer.getSize(reason));
        buf.putByte(SimpleSgsProtocol.LOGIN_FAILURE).
            putString(reason);
        writeToWriteHandler(ByteBuffer.wrap(buf.getBuffer()));
	flushMessageQueue();
	acceptor.monitorDisconnection(this);
    }
    
    /**
     * Notifies the associated client that it has successfully logged out.
     */
    private void logoutSuccess() {
	ByteBuffer buf = ByteBuffer.allocate(1);
	buf.put(SimpleSgsProtocol.LOGOUT_SUCCESS).
	    flip();
	writeToWriteHandler(buf);
	acceptor.monitorDisconnection(this);
    }

    /**
     * Notifies the associated client that the previous relocation attempt
     * was successful.
     */
    private void relocateSuccess() {
	MessageBuffer buf = new MessageBuffer(1 + reconnectKey.length);
	buf.putByte(SimpleSgsProtocol.RELOCATE_SUCCESS).
	    putBytes(reconnectKey);
	writeToWriteHandler(ByteBuffer.wrap(buf.getBuffer()));
	flushMessageQueue();
    }

    /**
     * Notifies the associated client that the previous relocation attempt
     * was unsuccessful for the specified {@code reason}.  The specified
     * {@code throwable}, if non-{@code null} is an exception that
     * occurred while processing the relocation request.  The message
     * channel should be careful not to reveal to the associated client
     * sensitive data that may be present in the specified {@code
     * throwable}.
     *
     * @param	reason a reason why the relocation was unsuccessful
     * @param	throwable an exception that occurred while processing the
     *		relocation request, or {@code null}
     */
    private void relocateFailure(String reason, Throwable ignore) {
	// for now, override specified reason.
	reason = DEFAULT_RELOCATE_FAILED_REASON;
        MessageBuffer buf =
	    new MessageBuffer(1 + MessageBuffer.getSize(reason));
        buf.putByte(SimpleSgsProtocol.RELOCATE_FAILURE).
            putString(reason);
        writeToWriteHandler(ByteBuffer.wrap(buf.getBuffer()));
	flushMessageQueue();
	acceptor.monitorDisconnection(this);
    }
    
    /* -- Implement Channel -- */
    
    /** {@inheritDoc} */
    public boolean isOpen() {
        return asyncMsgChannel.isOpen();
    }

    /** {@inheritDoc} */
    public void close() {
	if (isOpen()) {
	    try {
		asyncMsgChannel.close();
	    } catch (IOException e) {
	    }
	}
	readHandler = new ClosedReadHandler();
        writeHandler = new ClosedWriteHandler();
	if (protocolHandler != null) {
	    protocolHandler.disconnect(new RequestHandler());
	}
    }

    /* -- Methods for reading and writing -- */
    
    /**
     * Schedules an asynchronous task to resume reading.
     */
    protected void scheduleReadOnReadHandler() {
	acceptor.scheduleNonTransactionalTask(
	    new AbstractKernelRunnable("ResumeReadOnReadHandler") {
		public void run() {
		    logger.log(
			Level.FINER, "resuming reads protocol:{0}", this);
		    if (isOpen()) {
			readHandler.read();
		    }
		} });
    }

    /**
     * Writes a message to the write handler if login has been handled,
     * otherwise enqueues the message to be sent when the login has not yet been
     * handled.
     *
     * @param	buf a buffer containing a complete protocol message
     */
    private void writeOrEnqueueIfLoginNotHandled(ByteBuffer buf) {
	synchronized (lock) {
	    if (!loginHandled) {
		messageQueue.add(buf);
	    } else {
		writeToWriteHandler(buf);
	    }
	}
    }
    
    /**
     * Writes a message to the write handler.
     *
     * @param	buf a buffer containing a complete protocol message
     */
    private void writeToWriteHandler(ByteBuffer message) {
	try {
	    writeHandler.write(message);
		    
	} catch (RuntimeException e) {
	    if (logger.isLoggable(Level.WARNING)) {
		logger.logThrow(
		    Level.WARNING, e,
		    "writeToWriteHandler protocol:{0} throws", this);
	    }
	}
    }

    /**
     * Writes all enqueued messages to the write handler.
     */
    private void flushMessageQueue() {
	synchronized (lock) {
	    loginHandled = true;
	    for (ByteBuffer nextMessage : messageQueue) {
		writeToWriteHandler(nextMessage);
	    }
	    messageQueue.clear();
	}
    }
    
    /**
     * Returns the next reconnect key.
     *
     * @return the next reconnect key
     */
    private static byte[] getNextReconnectKey() {
	byte[] key = new byte[DEFAULT_RECONNECT_KEY_LENGTH];
	random.nextBytes(key);
	return key;
    }

    /**
     * Throws {@link IllegalStateException} if the client session is
     * relocating or has suspended messages.
     *
     * @throws	IllegalStateException if the client session is relocating
     */
    private void checkSuspend() {
	synchronized (lock) {
	    if (relocationInfo != null) {
		throw new IllegalStateException("session relocating");
	    } else if (suspendCompletionFuture != null) {
		throw new IllegalStateException("messages suspended");
	    }
	}
    }

    /* -- I/O completion handlers -- */

    /** A completion handler for writing to a connection. */
    private abstract class WriteHandler
        implements CompletionHandler<Void, Void>
    {
	/** Writes the specified message. */
        abstract void write(ByteBuffer message);
    }

    /** A completion handler for writing that always fails. */
    private class ClosedWriteHandler extends WriteHandler {

	ClosedWriteHandler() { }

        @Override
        void write(ByteBuffer message) {
            throw new ClosedAsynchronousChannelException();
        }
        
        public void completed(IoFuture<Void, Void> result) {
            throw new AssertionError("should be unreachable");
        }    
    }

    /** A completion handler for writing to the session's channel. */
    private class ConnectedWriteHandler extends WriteHandler {

	/** The lock for accessing the fields {@code pendingWrites} and
	 * {@code isWriting}. The locks {@code lock} and {@code writeLock}
	 * should only be acquired in that specified order.
	 */
	private final Object writeLock = new Object();
	
	/** An unbounded queue of messages waiting to be written. */
        private final LinkedList<ByteBuffer> pendingWrites =
            new LinkedList<ByteBuffer>();

	/** Whether a write is underway. */
        private boolean isWriting = false;

	/** Creates an instance of this class. */
        ConnectedWriteHandler() { }

	/**
	 * Adds the message to the queue, and starts processing the queue if
	 * needed.
	 */
        @Override
        void write(ByteBuffer message) {
            if (message.remaining() > SimpleSgsProtocol.MAX_PAYLOAD_LENGTH) {
                throw new IllegalArgumentException(
                    "message too long: " + message.remaining() + " > " +
                        SimpleSgsProtocol.MAX_PAYLOAD_LENGTH);
            }
            boolean first;
            synchronized (writeLock) {
                first = pendingWrites.isEmpty();
                pendingWrites.add(message);
            }
            if (logger.isLoggable(Level.FINEST)) {
                logger.log(Level.FINEST,
			   "write protocol:{0} message:{1} first:{2}",
                           SimpleSgsProtocolImpl.this,
			   HexDumper.format(message, 0x50), first);
            }
            if (first) {
                processQueue();
            }
        }

	/** Start processing the first element of the queue, if present. */
        private void processQueue() {
            ByteBuffer message;
            synchronized (writeLock) {
                if (isWriting) {
                    return;
		}
                message = pendingWrites.peek();
                if (message == null) {
		    return;
		}
		isWriting = true;
            }
            if (logger.isLoggable(Level.FINEST)) {
                logger.log(
		    Level.FINEST,
		    "processQueue protocol:{0} size:{1,number,#} head={2}",
		    SimpleSgsProtocolImpl.this, pendingWrites.size(),
		    HexDumper.format(message, 0x50));
            }
            try {
                asyncMsgChannel.write(message, this);
            } catch (RuntimeException e) {
                logger.logThrow(Level.SEVERE, e,
				"{0} processing message {1}",
				SimpleSgsProtocolImpl.this,
				HexDumper.format(message, 0x50));
                throw e;
            }
        }

	/** Done writing the first request in the queue. */
        public void completed(IoFuture<Void, Void> result) {
	    ByteBuffer message;
            synchronized (writeLock) {
                message = pendingWrites.remove();
                isWriting = false;
            }
            if (logger.isLoggable(Level.FINEST)) {
		ByteBuffer resetMessage = message.duplicate();
		resetMessage.reset();
                logger.log(Level.FINEST,
			   "completed write protocol:{0} message:{1}",
			   SimpleSgsProtocolImpl.this,
			   HexDumper.format(resetMessage, 0x50));
            }
            try {
                result.getNow();
                /* Keep writing */
                processQueue();
            } catch (ExecutionException e) {
                /*
		 * TBD: If we're expecting the session to close, don't
                 * complain.
		 */
                if (logger.isLoggable(Level.FINE)) {
                    logger.logThrow(Level.FINE, e,
				    "write protocol:{0} message:{1} throws",
				    SimpleSgsProtocolImpl.this,
				    HexDumper.format(message, 0x50));
                }
		synchronized (writeLock) {
		    pendingWrites.clear();
		}
		close();
            }
        }
    }

    /** A completion handler for reading from a connection. */
    private abstract class ReadHandler
        implements CompletionHandler<ByteBuffer, Void>
    {
	/** Initiates the read request. */
        abstract void read();
    }

    /** A completion handler for reading that always fails. */
    private class ClosedReadHandler extends ReadHandler {

	ClosedReadHandler() { }

        @Override
        void read() {
            throw new ClosedAsynchronousChannelException();
        }

        public void completed(IoFuture<ByteBuffer, Void> result) {
            throw new AssertionError("should be unreachable");
        }
    }

    /** A completion handler for reading from the session's channel. */
    private class ConnectedReadHandler extends ReadHandler {

	/** The lock for accessing the {@code isReading} field. The locks
	 * {@code lock} and {@code readLock} should only be acquired in
	 * that specified order.
	 */
	private final Object readLock = new Object();

	/** Whether a read is underway. */
        private boolean isReading = false;

	/** Creates an instance of this class. */
        ConnectedReadHandler() { }

	/** Reads a message from the connection. */
        @Override
        void read() {
            synchronized (readLock) {
                if (isReading) {
                    throw new ReadPendingException();
		}
                isReading = true;
            }
            asyncMsgChannel.read(this);
        }

	/** Handles the completed read operation. */
        public void completed(IoFuture<ByteBuffer, Void> result) {
            synchronized (readLock) {
                isReading = false;
            }
            try {
                ByteBuffer message = result.getNow();
                if (message == null) {
		    close();
                    return;
                }
                if (logger.isLoggable(Level.FINEST)) {
                    logger.log(
                        Level.FINEST,
                        "completed read protocol:{0} message:{1}",
                        SimpleSgsProtocolImpl.this,
			HexDumper.format(message, 0x50));
                }

                byte[] payload = new byte[message.remaining()];
                message.get(payload);

                // Dispatch
                bytesReceived(payload);

            } catch (Exception e) {

                /*
		 * TBD: If we're expecting the channel to close, don't
                 * complain.
		 */

                if (logger.isLoggable(Level.FINE)) {
                    logger.logThrow(
                        Level.FINE, e,
                        "Read completion exception {0}", asyncMsgChannel);
                }
                close();
            }
        }

	/** Processes the received message. */
        private void bytesReceived(byte[] buffer) {

	    MessageBuffer msg = new MessageBuffer(buffer);
	    byte opcode = msg.getByte();
	    byte version;

	    if (logger.isLoggable(Level.FINEST)) {
		logger.log(
 		    Level.FINEST,
		    "processing opcode 0x{0}",
		    Integer.toHexString(opcode));
	    }
	    
	    switch (opcode) {
		
	    case SimpleSgsProtocol.LOGIN_REQUEST:

		version = msg.getByte();
	        if (version != SimpleSgsProtocol.VERSION) {
	            if (logger.isLoggable(Level.SEVERE)) {
	                logger.log(Level.SEVERE,
	                    "got protocol version:{0}, " +
	                    "expected {1}", version, SimpleSgsProtocol.VERSION);
	            }
		    close();
	            break;
	        }

		String name = msg.getString();
		String password = msg.getString();

		try {
		    identity = acceptor.authenticate(name, password);
		} catch (Exception e) {
		    logger.logThrow(
			Level.FINEST, e,
			"login authentication failed for name:{0}", name);
		    loginFailure("login failed", e);
		    
		    break;
		}

		listener.newLogin(
 		    identity, SimpleSgsProtocolImpl.this, new LoginHandler());
		
                // Resume reading immediately
		read();

		break;

	    case SimpleSgsProtocol.RELOCATE_REQUEST:

		version = msg.getByte();
	        if (version != SimpleSgsProtocol.VERSION) {
	            if (logger.isLoggable(Level.SEVERE)) {
	                logger.log(Level.SEVERE,
	                    "got protocol version:{0}, " +
	                    "expected {1}", version, SimpleSgsProtocol.VERSION);
	            }
		    close();
	            break;
	        }

		byte[] keyBytes = msg.getBytes(msg.limit() - msg.position());
		BigInteger relocationKey = new BigInteger(1, keyBytes);
		
		listener.relocatedSession(
 		    relocationKey, SimpleSgsProtocolImpl.this,
		    new RelocateHandler());
		
                // Resume reading immediately
		read();

		break;
		
		
	    case SimpleSgsProtocol.SESSION_MESSAGE:
		ByteBuffer clientMessage =
		    ByteBuffer.wrap(msg.getBytes(msg.limit() - msg.position()));
		if (protocolHandler == null) {
		    // ignore message before authentication
		    if (logger.isLoggable(Level.FINE)) {
			logger.log(
			    Level.FINE,
			    "Dropping early session message:{0} " +
			    "for protocol:{1}",
			    HexDumper.format(clientMessage, 0x50),
			    SimpleSgsProtocolImpl.this);
		    }
		    return;
		}

		// TBD: schedule a task to process this message?
		protocolHandler.sessionMessage(clientMessage,
					       new RequestHandler());
		break;

	    case SimpleSgsProtocol.CHANNEL_MESSAGE:
		BigInteger channelRefId =
		    new BigInteger(1, msg.getBytes(msg.getShort()));
		ByteBuffer channelMessage =
		    ByteBuffer.wrap(msg.getBytes(msg.limit() - msg.position()));
		if (protocolHandler == null) {
		    // ignore message before authentication
		    if (logger.isLoggable(Level.FINE)) {
			logger.log(
			    Level.FINE,
			    "Dropping early channel message:{0} " +
			    "for protocol:{1}",
			    HexDumper.format(channelMessage, 0x50),
			    SimpleSgsProtocolImpl.this);
		    }
		    return;
		}
		
		// TBD: schedule a task to process this message?
		protocolHandler.channelMessage(
		    channelRefId, channelMessage, new RequestHandler());
		break;

	    case SimpleSgsProtocol.SUSPEND_MESSAGES_COMPLETE:
		synchronized (lock) {
		    if (suspendCompletionFuture != null) {
			suspendCompletionFuture.done();
		    }
		    if (relocationInfo != null) {
			relocationInfo.completed();
		    }
		}
		break;

	    case SimpleSgsProtocol.LOGOUT_REQUEST:
		if (protocolHandler == null) {
		    close();
		    return;
		}
		protocolHandler.logoutRequest(new LogoutHandler());

		// Resume reading immediately
                read();

		break;
		
	    default:
		if (logger.isLoggable(Level.SEVERE)) {
		    logger.log(
			Level.SEVERE,
			"unknown opcode 0x{0}",
			Integer.toHexString(opcode));
		}
		close();
		break;
	    }
	}
    }

    /**
     * A completion handler that is notified when the associated login
     * request has completed processing. 
     */
    private class LoginHandler
	implements RequestCompletionHandler<SessionProtocolHandler>
    {
	/** {@inheritDoc}
	 *
	 * <p>This implementation invokes the {@code get} method on the
	 * specified {@code future} to obtain the session's protocol
	 * handler.
	 *
	 * <p>If the login request completed successfully (without throwing an
	 * exception), it sends a logout success message to the client.
	 *
	 * <p>Otherwise, if the {@code get} invocation throws an {@code
	 * ExecutionException} and the exception's cause is a {@link
	 * LoginRedirectException}, it sends a login redirect message to
	 * the client with the redirection information obtained from the
	 * exception.  If the {@code ExecutionException}'s cause is a
	 * {@link LoginFailureException}, it sends a login failure message
	 * to the client.
	 *
	 * <p>If the {@code get} method throws an exception other than
	 * {@code ExecutionException}, or the {@code ExecutionException}'s
	 * cause is not either a {@code LoginFailureException} or a {@code
	 * LoginRedirectException}, then a login failed message is sent to
	 * the client.
	 */
	public void completed(Future<SessionProtocolHandler> future) {
	    try {
		protocolHandler = future.get();
		loginSuccess();
		
	    } catch (ExecutionException e) {
		// login failed
		Throwable cause = e.getCause();
		if (cause instanceof LoginRedirectException) {
		    // redirect
		    LoginRedirectException redirectException =
			(LoginRedirectException) cause;
		    
                    loginRedirect(redirectException.getNode(),
                                  redirectException.getProtocolDescriptors());
		    
		} else if (cause instanceof LoginFailureException) {
		    loginFailure(cause.getMessage(), cause.getCause());
		} else {
		    loginFailure(e.getMessage(), e.getCause());
		}
	    } catch (Exception e) {
		loginFailure(e.getMessage(), e.getCause());
	    }
	}
    }

    /**
     * A completion handler that is notified when the associated relocate
     * request has completed processing. 
     */
    private class RelocateHandler
	implements RequestCompletionHandler<SessionProtocolHandler>
    {
	/** {@inheritDoc}
	 *
	 * <p>This implementation invokes the {@code get} method on the
	 * specified {@code future} to obtain the session's protocol
	 * handler.
	 *
	 * <p>If the relocate request completed successfully (without
	 * throwing an exception), it sends a relocate success message to
	 * the client.
	 *
	 * <p>Otherwise, if the {@code get} invocation throws an {@code
	 * ExecutionException} and the exception's cause is a {@link
	 * LoginRedirectException}, it sends a login redirect message to
	 * the client with the redirection information obtained from the
	 * exception.  If the {@code ExecutionException}'s cause is a
	 * {@link LoginFailureException}, it sends a relocate failure
	 * message to the client.
	 *
	 * <p>If the {@code get} method throws an exception other than
	 * {@code ExecutionException}, or the {@code ExecutionException}'s
	 * cause is not either a {@code LoginFailureException} or a {@code
	 * LoginRedirectException}, then a relocate failed message is sent
	 * to the client.
	 */
	public void completed(Future<SessionProtocolHandler> future) {
	    try {
		protocolHandler = future.get();
		relocateSuccess();
		
	    } catch (ExecutionException e) {
		// relocate failed
		Throwable cause = e.getCause();
		if (cause instanceof LoginRedirectException ||
		    cause instanceof RelocateFailureException) {
		    relocateFailure(cause.getMessage(), cause.getCause());
		} else {
		    relocateFailure(e.getMessage(), e.getCause());
		}
	    } catch (Exception e) {
		relocateFailure(e.getMessage(), e.getCause());
	    }
	}
    }
    
    /**
     * A completion handler that is notified when its associated request has
     * completed processing. 
     */
    private class RequestHandler implements RequestCompletionHandler<Void> {
	
	/**
	 * {@inheritDoc}
	 *
	 * <p>This implementation schedules a task to resume reading.
	 */
	public void completed(Future<Void> future) {
	    try {
		future.get();
	    } catch (ExecutionException e) {
		if (logger.isLoggable(Level.FINE)) {
		    logger.logThrow(
			Level.FINE, e, "Obtaining request result throws ");
		}

		Throwable cause = e.getCause();
		if (cause instanceof RequestFailureException) {
		    FailureReason reason =
			((RequestFailureException) cause).getReason();
		    if (reason.equals(FailureReason.DISCONNECT_PENDING) ||
			reason.equals(FailureReason.RELOCATE_PENDING)) {
			// Don't read any more from client because session
			// is either disconnecting or relocating.
			return;
		    }
		    // Assume other failures are transient.
		}

	    } catch (Exception e) {
		// TBD: Unknown exception: disconnect?
		if (logger.isLoggable(Level.WARNING)) {
		    logger.logThrow(
			Level.WARNING, e, "Obtaining request result throws ");
		}
	    }
	    scheduleReadOnReadHandler();
	}
    }
    
    /**
     * A completion handler that is notified when the associated logout
     * request has completed processing. 
     */
    private class LogoutHandler implements RequestCompletionHandler<Void> {

	/** {@inheritDoc}
	 *
	 * <p>This implementation sends a logout success message to the
	 * client .
	 */
	public void completed(Future<Void> future) {
	    try {
		future.get();
	    } catch (Exception e) {
		if (logger.isLoggable(Level.WARNING)) {
		    logger.logThrow(
			Level.WARNING, e, "Obtaining logout result throws ");
		}
	    }
	    logoutSuccess();
	}
    }
    
    /**
     * Relocation information.
     */
    private class RelocationInfo {

	private final Set<ProtocolDescriptor> descriptors;
	private final ByteBuffer relocationKey;

	RelocationInfo(Set<ProtocolDescriptor> descriptors,
		       ByteBuffer relocationKey)
	{
	    this.descriptors = descriptors;
	    this.relocationKey = relocationKey;
	}

	void completed() {
	    for (ProtocolDescriptor descriptor : descriptors) {
		if (acceptor.getDescriptor().supportsProtocol(descriptor)) {
		    byte[] redirectionData =
			((SimpleSgsProtocolDescriptor) descriptor).
			getConnectionData();
		    ByteBuffer buf =
			ByteBuffer.allocate(1 + redirectionData.length +
					    relocationKey.remaining());
		    buf.put(SimpleSgsProtocol.RELOCATE_NOTIFICATION).
			put(redirectionData).
			put(relocationKey).
			flip();
		    writeToWriteHandler(buf);
		    flushMessageQueue();
		    acceptor.monitorDisconnection(SimpleSgsProtocolImpl.this);
		    return;
		}
	    }
	}
    }

    /**
     * A completion future for suspending messages.
     */
    private static class SuspendMessagesCompletionFuture
	extends AbstractCompletionFuture<Void>
    {
	SuspendMessagesCompletionFuture(RequestCompletionHandler<Void>
					completionFuture)
	{
	    super(completionFuture);
	}

	/** {@inheritDoc} */
	protected Void getValue() { return null; }
	
	/** {@inheritDoc} */
	public void done() {
	    super.done();
	}
    }
}
