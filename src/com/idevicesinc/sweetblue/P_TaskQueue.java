package com.idevicesinc.sweetblue;

import java.util.ArrayList;
import java.util.List;

import android.os.Handler;
import android.os.Looper;

class P_TaskQueue
{
	private final ArrayList<PA_Task> m_queue = new ArrayList<PA_Task>();
	private PA_Task m_current;
	private long m_updateCount;
	private final P_Logger m_logger;
	private final BleManager m_mngr;
	private double m_time = 0.0;
	
	private Handler m_executeHandler = null;
	
	private int m_currentOrdinal;
	
	P_TaskQueue(BleManager mngr)
	{
		m_mngr = mngr;
		m_logger = mngr.getLogger();
		
		initHandler(); 
	}
	
	int assignOrdinal()
	{
		final int toReturn = m_currentOrdinal;
		
		m_currentOrdinal++;
		
		return toReturn;
	}

	public Handler getExecuteHandler()
	{
		return m_executeHandler;
	}
	
	int getCurrentOrdinal()
	{
		return m_currentOrdinal;
	}

	public PA_Task peek()
	{
		return m_queue.size() > 0 ? m_queue.get(0) : null;
	}
	
	private void initHandler()
	{
		final Thread thread = new Thread()
		{
			@Override public void run()
			{
				Looper.prepare();
				m_executeHandler = new Handler(Looper.myLooper());
				Looper.loop();
			}
		};
		
		thread.start();
	}
	
	private boolean tryCancellingCurrentTask(PA_Task newTask)
	{
		if( getCurrent() != null && getCurrent().isCancellableBy(newTask) )
		{
//			int soonestSpot = U_BtTaskQueue.findSoonestSpot(m_queue, newTask);
			
//			if( soonestSpot == 0 )
			{
				endCurrentTask(PE_TaskState.CANCELLED);
				addAtIndex(newTask, 0);
				
				return true;
			}
		}
		
		return false;
	}
	
	private boolean tryInterruptingCurrentTask(PA_Task newTask)
	{
		if( getCurrent() != null && getCurrent().isInterruptableBy(newTask) )
		{
//			int soonestSpot = U_BtTaskQueue.findSoonestSpot(m_queue, newTask);
			
//			if( soonestSpot == 0 )
			{
				PA_Task current_saved = getCurrent();
				endCurrentTask(PE_TaskState.INTERRUPTED);
				addAtIndex(newTask, 0);
				addAtIndex(current_saved, 1);
				
				return true;
			}
		}
		
		return false;
	}
	
	private boolean tryInsertingIntoQueue(PA_Task newTask)
	{
		int soonestSpot = PU_TaskQueue.findSoonestSpot(m_queue, newTask);
		
		if( soonestSpot >= 0 )
		{
			addAtIndex(newTask, soonestSpot);
			
			return true;
		}
		
		return false;
	}
	
	private void addToBack(PA_Task task)
	{
		addAtIndex(task, -1);
	}
	
	public void softlyCancelTasks(PA_Task task)
	{
		for( int i = 0; i < m_queue.size()-1; i++ )
		{
			PA_Task ithTask = m_queue.get(i);
			if( ithTask.isSoftlyCancellableBy(task) )
			{
				ithTask.attemptToSoftlyCancel(task);
			}
		}
		
		if( getCurrent() != null )
		{
			if( getCurrent().isSoftlyCancellableBy(task) )
			{
				getCurrent().attemptToSoftlyCancel(task);
			}
		}
	}
	
	private void addAtIndex(PA_Task task, int index)
	{
		if( index >= 0 )
		{
			m_queue.add(index, task);
		}
		else
		{
			m_queue.add(task);
			
			index = m_queue.size()-1;
		}

		task.assignDefaultOrdinal(this);
		
		softlyCancelTasks(task);
		
		task.onAddedToQueue(this);
		
		print();
	}
	
	public void add(final PA_Task newTask)
	{
		newTask.init();

		if( m_mngr.getUpdateLoop().postNeeded() )
		{
			m_mngr.getUpdateLoop().postIfNeeded(new Runnable()
			{
				@Override
				public void run()
				{
					add_mainThread(newTask);
				}
			});
		}
		else
		{
			add_mainThread(newTask);
		}
	}

	private void add_mainThread(final PA_Task newTask)
	{
		if( tryCancellingCurrentTask(newTask) )
		{
			dequeue();
		}
		else if( tryInterruptingCurrentTask(newTask) ) {}
		else if( tryInsertingIntoQueue(newTask) ) {}
		else { addToBack(newTask); }

		while( getCurrent() == null && m_queue.size() > 0 )
		{
			dequeue();
		}
	}
	
	double getTime()
	{
		return m_time;
	}
	
	public void update(double timeStep)
	{
		m_time += timeStep;
		
		if( m_executeHandler == null )
		{
			m_logger.d("Waiting for execute handler to initialize.");
			
			return;
		}

		if( m_current == null )
		{
			dequeue();
		}
		
		if( getCurrent() != null )
		{			
			getCurrent().update_internal(timeStep);
		}
		
		m_updateCount++;
	}
	
	private void dequeue()
	{
		if( !m_mngr.ASSERT(m_current == null) )  return;
		if( m_queue.size() == 0 )  return;
		
		for( int i = 0; i < m_queue.size(); i++ )
		{
			PA_Task newPotentialCurrent = m_queue.get(i);
			
			if( newPotentialCurrent.isArmable() )
			{
				m_queue.remove(i);
				m_current = newPotentialCurrent;
				m_current.arm();
				m_current.tryExecuting();
				
				break;
			}
		}
		
		print();
	}
	
	public long getUpdateCount()
	{
		return m_updateCount;
	}
	
	public PA_Task getCurrent()
	{
//		return m_pendingEndingStateForCurrentTask != null ? null : m_current;
		return m_current;
	}
	
	private boolean endCurrentTask(PE_TaskState endingState)
	{
		if( !m_mngr.ASSERT(endingState.isEndingState()) )	return false;
		if( getCurrent() == null ) 							return false;
//		if( m_pendingEndingStateForCurrentTask != null )	return false;
		
		PA_Task current_saved = m_current;
		m_current = null;
		current_saved.setEndingState(endingState);

		if( m_queue.size() > 0 && getCurrent() == null )
		{
			if( endingState.canGoToNextTaskImmediately() )
			{
				dequeue();
			}
			else
			{
				//--- DRK > Posting to prevent potential stack overflow if queue is really big and all tasks are failing in a row.
				m_mngr.getUpdateLoop().forcePost(new Runnable()
				{
					@Override public void run()
					{
						if( m_queue.size() > 0 && getCurrent() == null )
						{
							dequeue();
						}
					}
				});
			}
		}
		
		print();
		
//		m_pendingEndingStateForCurrentTask = endingState;
		
		return true;
	}
	
	public void interrupt(Class<? extends PA_Task> taskClass, BleManager manager)
	{
		PA_Task current = getCurrent(taskClass, manager);
		
		if( PU_TaskQueue.isMatch(getCurrent(), taskClass, manager, null, null) )
		{
			tryEndingTask(current, PE_TaskState.INTERRUPTED);
			
			add(current);
		}
	}

	
	public boolean succeed(Class<? extends PA_Task> taskClass, BleManager manager)
	{
		return tryEndingTask(taskClass, manager, null, null, PE_TaskState.SUCCEEDED);
	}
	
	public boolean succeed(Class<? extends PA_Task> taskClass, BleDevice device)
	{
		return tryEndingTask(taskClass, null, device, null, PE_TaskState.SUCCEEDED);
	}
	
	public boolean succeed(Class<? extends PA_Task> taskClass, BleServer server)
	{
		return tryEndingTask(taskClass, null, null, server, PE_TaskState.SUCCEEDED);
	}
	
	
	public boolean fail(Class<? extends PA_Task> taskClass, BleManager manager)
	{
		return tryEndingTask(taskClass, manager, null, null, PE_TaskState.FAILED);
	}
	
	public boolean fail(Class<? extends PA_Task> taskClass, BleDevice device)
	{
		return tryEndingTask(taskClass, null, device, null, PE_TaskState.FAILED);
	}

	public boolean fail(Class<? extends PA_Task> taskClass, BleServer server)
	{
		return tryEndingTask(taskClass, null, null, server, PE_TaskState.FAILED);
	}
	
	private boolean tryEndingTask(final Class<? extends PA_Task> taskClass, final BleManager mngr_nullable, final BleDevice device_nullable, final BleServer server_nullable, final PE_TaskState endingState)
	{
		if( PU_TaskQueue.isMatch(getCurrent(), taskClass, mngr_nullable, device_nullable, server_nullable ) )
		{
			return endCurrentTask(endingState);
		}
		
		return false;
	}
	
	void tryEndingTask(final PA_Task task, final PE_TaskState endingState)
	{
		if( m_mngr.getUpdateLoop().postNeeded() )
		{
			m_mngr.getUpdateLoop().postIfNeeded(new Runnable()
			{
				@Override public void run()
				{
					tryEndingTask_mainThread(task, endingState);
				}
			});
		}
		else
		{
			tryEndingTask_mainThread(task, endingState);
		}
	}

	private void tryEndingTask_mainThread(final PA_Task task, final PE_TaskState endingState)
	{
		if( task != null && task == getCurrent() )
		{
			if( !endCurrentTask(endingState) )
			{
				m_mngr.ASSERT(false);
			}
		}
	}
	
	public boolean isCurrent(Class<? extends PA_Task> taskClass, BleManager mngr)
	{
		return PU_TaskQueue.isMatch(getCurrent(), taskClass, mngr, null, null);
	}
	
	public boolean isCurrent(Class<? extends PA_Task> taskClass, BleDevice device)
	{
		return PU_TaskQueue.isMatch(getCurrent(), taskClass, null, device, null);
	}
	public boolean isCurrent(Class<? extends PA_Task> taskClass, BleServer server)
	{
		return PU_TaskQueue.isMatch(getCurrent(), taskClass, null, null, server);
	}
	
	private boolean isInQueue(Class<? extends PA_Task> taskClass, BleManager mngr_nullable, BleDevice device_nullable, BleServer server_nullable)
	{
		for( int i = 0; i < m_queue.size(); i++ )
		{
			if( PU_TaskQueue.isMatch(m_queue.get(i), taskClass, mngr_nullable, device_nullable, server_nullable) )
			{
				return true;
			}
		}
		
		return false;
	}
	
	public int getSize()
	{
		return m_queue.size();
	}

	public List<PA_Task> getRaw()
	{
		return m_queue;
	}
	
	public boolean isInQueue(Class<? extends PA_Task> taskClass, BleManager mngr)
	{
		return isInQueue(taskClass, mngr, null, null);
	}
	
	public boolean isInQueue(Class<? extends PA_Task> taskClass, BleDevice device)
	{
		return isInQueue(taskClass, null, device, null);
	}
	
	public boolean isInQueue(Class<? extends PA_Task> taskClass, BleServer server)
	{
		return isInQueue(taskClass, null, null, server);
	}
	
	public boolean isCurrentOrInQueue(Class<? extends PA_Task> taskClass, BleManager mngr)
	{
		return isCurrent(taskClass, mngr) || isInQueue(taskClass, mngr);
	}
	
	public <T extends PA_Task> T get(Class<? extends PA_Task> taskClass, BleManager mngr)
	{
		if( PU_TaskQueue.isMatch(getCurrent(), taskClass, mngr, null, null) )
		{
			return (T) getCurrent();
		}
		
		for( int i = 0; i < m_queue.size(); i++ )
		{
			if( PU_TaskQueue.isMatch(m_queue.get(i), taskClass, mngr, null, null) )
			{
				return (T) m_queue.get(i);
			}
		}
		
		return null;
	}
	
	public <T extends PA_Task> T getCurrent(Class<? extends PA_Task> taskClass, BleDevice device)
	{
		if( PU_TaskQueue.isMatch(getCurrent(), taskClass, null, device, null) )
		{
			return (T) getCurrent();
		}
		
		return null;
	}
	
	public <T extends PA_Task> T getCurrent(Class<? extends PA_Task> taskClass, BleManager mngr)
	{
		if( PU_TaskQueue.isMatch(getCurrent(), taskClass, mngr, null, null) )
		{
			return (T) getCurrent();
		}
		
		return null;
	}
	
	public <T extends PA_Task> T getCurrent(Class<? extends PA_Task> taskClass, BleServer server)
	{
		if( PU_TaskQueue.isMatch(getCurrent(), taskClass, null, null, server) )
		{
			return (T) getCurrent();
		}
		
		return null;
	}
	
	void print()
	{
		if( m_logger.isEnabled() )
		{
			m_logger.i(this.toString());
		}
	}
	
	private void clearQueueOf$removeFromQueue(int index)
	{
		PA_Task task = m_queue.remove(index);

		if( task.wasSoftlyCancelled() )
		{
			task.setEndingState(PE_TaskState.SOFTLY_CANCELLED);
		}
		else
		{
			task.setEndingState(PE_TaskState.CLEARED_FROM_QUEUE);
		}
		
		print();
	}
	
	public void clearQueueOf(Class<? extends PA_Task> taskClass, BleManager mngr)
	{
		for( int i = m_queue.size()-1; i >= 0; i-- )
		{
			if( PU_TaskQueue.isMatch(m_queue.get(i), taskClass, mngr, null, null) )
			{
				clearQueueOf$removeFromQueue(i);
			}
		}
	}
	
	public void clearQueueOf(Class<? extends PA_Task> taskClass, BleDevice device, final int ordinal)
	{
		for( int i = m_queue.size()-1; i >= 0; i-- )
		{
			final PA_Task task_ith = m_queue.get(i);

			if( ordinal <= -1 || ordinal >= 0 && task_ith.getOrdinal() <= ordinal )
			{
				if( PU_TaskQueue.isMatch(task_ith, taskClass, null, device, null) )
				{
					clearQueueOf$removeFromQueue(i);
				}
			}
		}
	}
	
	public void clearQueueOf(Class<? extends PA_Task> taskClass, BleServer server)
	{
		for( int i = m_queue.size()-1; i >= 0; i-- )
		{
			if( PU_TaskQueue.isMatch(m_queue.get(i), taskClass, null, null, server) )
			{
				clearQueueOf$removeFromQueue(i);
			}
		}
	}

	@Override public String toString()
	{
		final String current = m_current != null ? m_current.toString() : "no current task";
//		if( m_pendingEndingStateForCurrentTask != null)
//		{
//			current += "(" + m_pendingEndingStateForCurrentTask.name() +")";
//		}
		
		final String queue = m_queue.size() > 0 ? m_queue.toString() : "[queue empty]";
		
		final String toReturn = current + " " + queue;
		
		return toReturn;
	}
}
