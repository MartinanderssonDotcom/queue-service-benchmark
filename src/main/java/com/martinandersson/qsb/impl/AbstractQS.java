package com.martinandersson.qsb.impl;

import com.martinandersson.qsb.api.Message;
import com.martinandersson.qsb.api.QueueService;
import java.util.Iterator;
import static java.util.Objects.requireNonNull;
import java.util.Queue;
import java.util.function.Function;

/**
 * Skeleton queue service implementation built on top of a {@code Map} that map
 * queue names to {@code Queue}s of messages.<p>
 * 
 * Access to these building blocks is routed through a {@link Lockable} which
 * determines if explicit locking is required and if so, how.<p>
 * 
 * The lockables are specified using a {@link Configuration} object.
 * 
 * @param <M>  concrete message implementation type
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public abstract class AbstractQS<M extends AbstractMessage> implements QueueService
{
    /**
     * With lazy eviction disabled (false), each completion of a message will
     * eagerly remove him from the queue and attempt to remove empty queues.<p>
     * 
     * If lazy eviction is enabled, then a message marked completed has no
     * effect on the map and queue. Instead, reader threads during message
     * iteration will delete completed messages.<p>
     * 
     * Do note that turning off lazy eviction will change delivery semantics of
     * {@code ReadWriteLockedQS} from exactly-once to at-least-once since
     * ReadWriteLockedQS currently use PojoMessage and this class - if eviction
     * is eager - will cause reader threads to grab messages concurrently. See
     * implementation and source code comments in poll().
     */
    private static final boolean LAZY_EVICTION = true;
    
    private final Configuration<M>.Read c;
    
    
    
    /**
     * Constructs a {@code AbstractQS}.
     * 
     * @param config  configuration object
     */
    public AbstractQS(Configuration<M> config) {
        c = config.read();
    }
    
    
    
    /**
     * {@inheritDoc}<p>
     * 
     * This implementation make the assumption that the remapping function
     * passed to {@code Map.compute()} is only called once.
     * 
     * @implNote
     * This implementation will first create a new instance of the message
     * container.<p>
     * 
     * The implementation will then use a map-write access to call
     * {@code Map.compute()}, providing a remapping function that push the
     * message to a preexisting queue or if the queue does not exist, a new
     * queue that is created inside the remapping function.<p>
     * 
     * If the remapping function push to a preexisting queue, then this queue
     * will be write-accessed. Access to a queue that the remapping function
     * created will bypass any locking in place as the queue instance is not yet
     * visible to other threads.
     */
    @Override
    public final void push(String queue, String message) {
        push0(c.messageFactory().apply(queue, message));
    }
    
    private void push0(M message) {
        boolean[] pushed = {false};
        
        c.map().write(map -> {
            map.compute(message.queue(), (key, old) -> {
                final Lockable<Queue<M>> lq;
                
                if (pushed[0]) {
                    throw new AssertionError("Remapping function called twice.");
                }
                
                if (old != null) {
                    old.write(q -> q.add(message));
                    lq = old;
                }
                else {
                    lq = c.queueFactory().get();
                    lq.unsafe(q -> q.add(message));
                }
                
                pushed[0] = true;
                return lq;
            });
        });
    }
    
    /**
     * {@inheritDoc}
     * 
     * @implNote
     * In the first step of this implementation, the map is read-accessed to get
     * hold of the queue. If the queue exist, it is read-accessed to iterate
     * through messages starting at the head. Each message is attempted to be
     * marked as grabbed by calling {@code AbstractMessage.tryGrab()}. First
     * such call that succeeds brake the iteration.<p>
     * 
     * In the second step, if the implementation has a strong reason to believe
     * the queue is empty, then an attempt to remove the queue from the map will
     * be performed. This entails one write-access of the map and if the queue
     * is still present, it will be confirmed empty by using a read-access.
     */
    @Override
    public final Message poll(String queue) {
        boolean[] empty = {true};
        
        final Message v = c.map().readGet(m -> {
            Lockable<Queue<M>> lq = m.get(queue);

            if (lq == null) {
                // ..don't try a delete, no need!
                empty[0] = false;
                return null;
            }
            
            // Next we need to grab the message. We write-access the queue if lazy
            // eviction is turned on because then, if we see a completed message
            // we remove also it. If lazy eviction is turned off, then is the job
            // of complete() to remove the queue eagerly and in this method call,
            // we ignore completed messages and may therefore resort to a
            // read-access of the queue.
            
            Function<Queue<M>, Message> grabber = q -> {
                Iterator<M> it = q.iterator();
                
                M msg = null;
                
                iteration: while (it.hasNext()) {
                    empty[0] = false;
                    
                    final M impl = it.next();
                    
                    switch (impl.tryGrab(c.timeout())) {
                        case COMPLETED:
                            if (LAZY_EVICTION) {
                                it.remove();
                            }
                        case ACTIVE:
                            // Try next message..
                            break;
                        case SUCCEEDED:
                            msg = impl;
                            break iteration;
                        default:
                            throw new UnsupportedOperationException();
                    }
                }
                
                return msg;
            };
            
            return LAZY_EVICTION ?
                    lq.writeGet(grabber) :
                    lq.readGet(grabber);
        });
        
        if (empty[0]) {
            tryDelete(queue);
        }
        
        return v;
    }
    
    /**
     * {@inheritDoc}<p>
     * 
     * @implNote
     * The only thing this method does is to mark the message as completed by
     * calling {@code AbstractMessage.complete()}.
     */
    @Override
    public final void complete(Message message) {
        if (!(requireNonNull(message) instanceof AbstractMessage)) {
            throw new IllegalArgumentException(
                    "Where the hell did you get this thing from?");
        }
        
        AbstractMessage impl = (AbstractMessage) message;
        
        impl.complete();
        
        if (!LAZY_EVICTION) {
            // Must write-access. Read lock can not be upgraded to a write lock.
            c.map().write(m -> {
                Lockable<Queue<M>> lq = m.get(impl.queue());
                
                if (lq != null && lq.writeGet(q -> q.remove(impl) && q.isEmpty())) {
                    tryDelete(impl.queue());
                }
            });
        }
    }
    
    private void tryDelete(String queue) {
        // computeIfPresent() will remove the entry if the queue is empty.
        c.map().writeGet(m -> m.computeIfPresent(queue, (key, old) ->
                old.readGet(q -> q.isEmpty() ? null : old)));
    }
}