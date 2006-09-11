
package com.sun.sgs.impl.manager;

import com.sun.sgs.app.ManagedReference;
import com.sun.sgs.app.ManagedRunnable;

import com.sun.sgs.app.TaskManager;

import com.sun.sgs.service.TaskService;


/**
 * This is a simple implementation of <code>TaskManager</code> that is the
 * default used.
 *
 * @since 1.0
 * @author James Megquier
 * @author Seth Proctor
 */
public class SimpleTaskManager extends TaskManager
{

    // the backing task service
    private TaskService taskService;

    /**
     * Creates an instance of <code>SimpleTaskManager</code>.
     *
     * @param taskService the backing service
     */
    public SimpleTaskManager(TaskService taskService) {
        super();

        this.taskService = taskService;
    }

    /**
     * Queues a task to run.
     *
     * @param taskReference the task to run
     */
    public void queueTask(ManagedReference<? extends ManagedRunnable>
            taskReference) {
        taskService.queueTask(taskReference);
    }

}
