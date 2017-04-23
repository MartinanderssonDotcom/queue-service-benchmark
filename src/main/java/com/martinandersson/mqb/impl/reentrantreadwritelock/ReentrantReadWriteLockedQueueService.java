package com.martinandersson.mqb.impl.reentrantreadwritelock;

import com.martinandersson.mqb.impl.AbstractQueueService;
import static com.martinandersson.mqb.impl.Configuration.message;
import static com.martinandersson.mqb.impl.Lockable.readWrite;
import com.martinandersson.mqb.impl.PojoMessage;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Queue service that use {@code ReentrantReadWriteLock} for synchronization.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class ReentrantReadWriteLockedQueueService extends AbstractQueueService<PojoMessage>
{
    /**
     * Constructs a {@code ReentrantReadWriteLockedQueueService}.
     * 
     * @param timeout  message timeout
     */
    public ReentrantReadWriteLockedQueueService(Duration timeout) {
        super(message(PojoMessage::new).
              timeout(timeout).
              map(readWrite(new HashMap<>(), new ReentrantReadWriteLock())).
              queue(readWrite(ArrayDeque::new, ReentrantReadWriteLock::new)));
    }
}