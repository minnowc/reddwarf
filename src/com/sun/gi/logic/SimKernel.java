package com.sun.gi.logic;

import com.sun.gi.comm.routing.*;
import com.sun.gi.logic.impl.SimThreadImpl;
import com.sun.gi.objectstore.*;

/**
 * <p>
 * Title: Sim Kernel
 * </p>
 * <p>
 * Description: This is the interface to the logic engine. Each game
 * (simulation) in a slice has its own run-time sim object that implements this
 * interface and provides the operating context for the game.
 * </p>
 * <p>
 * Copyright: Copyright (c) 2003
 * </p>
 * <p>
 * Company: Sun Microsystems, TMI
 * </p>
 * 
 * @author Jeff Kesselman
 * @version 1.0
 */

public interface SimKernel {
	/**
	 * Creates a transactional context used to access GLOs
	 * 
	 * @param appID
	 *            long The ID of the game creating the transaction
	 * @param loader
	 *            ClassLoader The ClassLoader to use to load the classes of
	 *            deserialized objects.
	 * @return Transaction An object that implemenst the Transaction interface.
	 */
	public Transaction newTransaction(long appID, ClassLoader loader);

	/**
	 * @return
	 */
	public ObjectStore getOstore();
	
	public void addSimulation(Simulation sim);
	
	public void simHasNewTask();

	public void removeSimulation(Simulation sim);

	/**
	 * @param impl
	 */
	public void returnToThreadPool(SimThreadImpl impl);
}
