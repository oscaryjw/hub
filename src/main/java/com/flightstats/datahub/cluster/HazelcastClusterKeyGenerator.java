package com.flightstats.datahub.cluster;

import com.flightstats.datahub.model.DataHubKey;
import com.flightstats.datahub.service.ChannelLockExecutor;
import com.flightstats.datahub.util.DataHubKeyGenerator;
import com.flightstats.datahub.util.TimeProvider;
import com.google.inject.Inject;
import com.hazelcast.core.AtomicNumber;
import com.hazelcast.core.HazelcastInstance;

import java.util.Date;
import java.util.concurrent.Callable;

public class HazelcastClusterKeyGenerator implements DataHubKeyGenerator {

	private final TimeProvider timeProvider;
	private final HazelcastInstance hazelcastInstance;
	private final ChannelLockExecutor channelLockExecutor;

	@Inject
	public HazelcastClusterKeyGenerator(TimeProvider timeProvider, HazelcastInstance hazelcastInstance, ChannelLockExecutor channelLockExecutor) {
		this.timeProvider = timeProvider;
		this.hazelcastInstance = hazelcastInstance;
		this.channelLockExecutor = channelLockExecutor;
	}

	@Override
	public DataHubKey newKey(final String channelName) {
		try {
			Callable<DataHubKey> keyGeneratingCallable = new KeyGeneratingCallable(channelName);
			return channelLockExecutor.execute(channelName, keyGeneratingCallable);
		} catch (Exception e) {
			throw new RuntimeException("Error generating new DataHubKey: ", e);
		}
	}

	private class KeyGeneratingCallable implements Callable<DataHubKey> {
		private final String channelName;

		KeyGeneratingCallable(String channelName) {
			this.channelName = channelName;
		}

		@Override
		public DataHubKey call() throws Exception {
			Date keyDate = determineKeyDate();
			short sequence = determineKeySequence();
			return new DataHubKey( keyDate, sequence );
		}

		private Date determineKeyDate() {
			AtomicNumber lastWriteDateMillis = hazelcastInstance.getAtomicNumber("CHANNEL_NAME_DATE:" + channelName);
			Date lastWriteDate = new Date(lastWriteDateMillis.get());
			Date currentDate = timeProvider.getDate();

			Date keyDate = currentDate.after(lastWriteDate) ? currentDate : lastWriteDate;
			lastWriteDateMillis.set(keyDate.getTime());
			return keyDate;
		}

		private short determineKeySequence() {
			AtomicNumber sequenceNumber = hazelcastInstance.getAtomicNumber("CHANNEL_NAME_SEQ:" + channelName);
			return sequenceNumber.compareAndSet(Short.MAX_VALUE, 0) ? Short.MAX_VALUE : (short) sequenceNumber.getAndAdd(1);
		}
	}
}
