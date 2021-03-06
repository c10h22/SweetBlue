package com.idevicesinc.sweetblue;

import android.annotation.SuppressLint;

class P_Task_Bond extends PA_Task_RequiresBleOn
{
	//--- DRK > Originally used because for tab 4 (and any other bonding failure during connection) we'd force disconnect from the connection failing
	//---		and then put another bond task on the queue, but because we hadn't actually yet killed the transaction lock, the bond task would
	//---		cut the unbond task in the queue. Not adding bonding task in the disconnect flow now though so this is probably useless for future use.
	static enum E_TransactionLockBehavior
	{
		PASSES,
		DOES_NOT_PASS;
	}
	
	private final PE_TaskPriority m_priority;
	private final boolean m_explicit;
	private final boolean m_partOfConnection;
	private final E_TransactionLockBehavior m_lockBehavior;
	
	private int m_failReason = BleStatuses.BOND_FAIL_REASON_NOT_APPLICABLE;
	
	public P_Task_Bond(BleDevice device, boolean explicit, boolean partOfConnection, I_StateListener listener, PE_TaskPriority priority, E_TransactionLockBehavior lockBehavior)
	{
		super(device, listener);
		
		m_priority = priority == null ? PE_TaskPriority.FOR_EXPLICIT_BONDING_AND_CONNECTING : priority;
		m_explicit = explicit;
		m_partOfConnection = partOfConnection;
		m_lockBehavior = lockBehavior;
	}
	
	public P_Task_Bond(BleDevice device, boolean explicit, boolean partOfConnection, I_StateListener listener, E_TransactionLockBehavior lockBehavior)
	{
		this(device, explicit, partOfConnection, listener, null, lockBehavior);
	}
	
	@Override public boolean isExplicit()
	{
		return m_explicit;
	}
	
	@SuppressLint("NewApi")
	@Override public void execute()
	{
		//--- DRK > Commenting out this block for now because Android can lie and tell us we're bonded when we're actually not,
		//---		so therefore we always try to force a bond regardless. Not sure if that actually forces
		//---		Android to "come clean" about its actual bond status or not, but worth a try.
		//---		UPDATE: No, it doesn't appear this works...Android lies even to itself, so commenting this back in.
		if( getDevice().m_nativeWrapper.isNativelyBonded() )
		{
			getLogger().w("Already bonded!");
			
			succeed();
			
			return;
		}
		
		if( getDevice().m_nativeWrapper./*already*/isNativelyBonding() )
		{
			// nothing to do
			
			return;
		}

		if( !m_explicit )
		{
			fail();
		}
		else if( !getDevice().getNative().createBond() )
		{
			failImmediately();

			getLogger().w("Bond failed immediately.");
		}
	}
	
	@Override public boolean isMoreImportantThan(PA_Task task)
	{
		if( task instanceof P_Task_TxnLock )
		{
			if( m_lockBehavior == E_TransactionLockBehavior.PASSES )
			{
				P_Task_TxnLock task_cast = (P_Task_TxnLock) task;
				
				if( this.getDevice() == task_cast.getDevice() )
				{
					return true;
				}
			}
		}
		
		return super.isMoreImportantThan(task);
	}
	
	public void onNativeFail(int failReason)
	{
		m_failReason = failReason;
		
		fail();
	}
	
	public int getFailReason()
	{
		return m_failReason;
	}
	
	@Override public PE_TaskPriority getPriority()
	{
		return m_priority;
	}
	
	@Override protected boolean isSoftlyCancellableBy(PA_Task task)
	{
		if( this.getDevice().equals(task.getDevice()) )
		{
			if( task.getClass() == P_Task_Disconnect.class )
			{
				if( this.m_partOfConnection && this.getState() == PE_TaskState.EXECUTING )
				{
					return true;
				}
			}
			else if( task.getClass() == P_Task_Unbond.class )
			{
				return true;
			}
		}
		
		return super.isSoftlyCancellableBy(task);
	}

	@Override protected BleTask getTaskType()
	{
		return BleTask.BOND;
	}
}
