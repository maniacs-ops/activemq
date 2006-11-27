/**
 * 
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package org.apache.activemq.store.rapid;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.activeio.journal.active.Location;
import org.apache.activemq.broker.ConnectionContext;
import org.apache.activemq.command.ActiveMQDestination;
import org.apache.activemq.command.Message;
import org.apache.activemq.command.MessageAck;
import org.apache.activemq.command.MessageId;
import org.apache.activemq.command.SubscriptionInfo;
import org.apache.activemq.kaha.ListContainer;
import org.apache.activemq.kaha.MapContainer;
import org.apache.activemq.kaha.Marshaller;
import org.apache.activemq.kaha.Store;
import org.apache.activemq.kaha.StoreEntry;
import org.apache.activemq.store.MessageRecoveryListener;
import org.apache.activemq.store.TopicMessageStore;
import org.apache.activemq.store.kahadaptor.ConsumerMessageRef;
import org.apache.activemq.store.kahadaptor.ConsumerMessageRefMarshaller;
import org.apache.activemq.store.kahadaptor.TopicSubAck;
import org.apache.activemq.store.kahadaptor.TopicSubContainer;


/**
 * A MessageStore that uses a Journal to store it's messages.
 * 
 * @version $Revision: 1.13 $
 */
public class RapidTopicMessageStore extends RapidMessageStore implements TopicMessageStore{

    private ListContainer ackContainer;
    private Map subscriberContainer;
    private Store store;
    private Map subscriberMessages=new ConcurrentHashMap();

    public RapidTopicMessageStore(RapidPersistenceAdapter adapter, Store store,ListContainer messageContainer,ListContainer ackContainer,
            MapContainer subsContainer,ActiveMQDestination destination,int maximumCacheSize) throws IOException{
        super(adapter,destination,messageContainer,maximumCacheSize);
        this.store=store;
        this.ackContainer=ackContainer;
        subscriberContainer=subsContainer;
        // load all the Ack containers
        for(Iterator i=subscriberContainer.keySet().iterator();i.hasNext();){
            Object key=i.next();
            addSubscriberMessageContainer(key);
        }
    }

    public synchronized void addMessage(ConnectionContext context,Message message) throws IOException{
        int subscriberCount=subscriberMessages.size();
        if(subscriberCount>0){
            final Location location = peristenceAdapter.writeCommand(message, message.isResponseRequired());
            final RapidMessageReference md = new RapidMessageReference(message, location);
            StoreEntry messageEntry=messageContainer.placeLast(md);
            TopicSubAck tsa=new TopicSubAck();
            tsa.setCount(subscriberCount);
            tsa.setMessageEntry(messageEntry);
            StoreEntry ackEntry=ackContainer.placeLast(tsa);
            for(Iterator i=subscriberMessages.values().iterator();i.hasNext();){
                TopicSubContainer container=(TopicSubContainer)i.next();
                ConsumerMessageRef ref=new ConsumerMessageRef();
                ref.setAckEntry(ackEntry);
                ref.setMessageEntry(messageEntry);
                container.getListContainer().add(ref);
            }
        }
    }

    public synchronized void acknowledge(ConnectionContext context,String clientId,String subscriptionName,
            MessageId messageId) throws IOException{
        String subcriberId=getSubscriptionKey(clientId,subscriptionName);
        TopicSubContainer container=(TopicSubContainer)subscriberMessages.get(subcriberId);
        if(container!=null){
            ConsumerMessageRef ref=(ConsumerMessageRef)container.getListContainer().removeFirst();
            if(ref!=null){
                TopicSubAck tsa=(TopicSubAck)ackContainer.get(ref.getAckEntry());
                if(tsa!=null){
                    if(tsa.decrementCount()<=0){
                        ackContainer.remove(ref.getAckEntry());
                        messageContainer.remove(tsa.getMessageEntry());
                    }else{
                        ackContainer.update(ref.getAckEntry(),tsa);
                    }
                }
            }
        }
    }

    public SubscriptionInfo lookupSubscription(String clientId,String subscriptionName) throws IOException{
        return (SubscriptionInfo)subscriberContainer.get(getSubscriptionKey(clientId,subscriptionName));
    }

    public synchronized void addSubsciption(String clientId,String subscriptionName,String selector,boolean retroactive)
            throws IOException{
        SubscriptionInfo info=new SubscriptionInfo();
        info.setDestination(destination);
        info.setClientId(clientId);
        info.setSelector(selector);
        info.setSubcriptionName(subscriptionName);
        String key=getSubscriptionKey(clientId,subscriptionName);
        // if already exists - won't add it again as it causes data files
        // to hang around
        if(!subscriberContainer.containsKey(key)){
            subscriberContainer.put(key,info);
        }
        ListContainer container=addSubscriberMessageContainer(key);
        if(retroactive){
            for(StoreEntry entry=ackContainer.getFirst();entry!=null;){
                TopicSubAck tsa=(TopicSubAck)ackContainer.get(entry);
                ConsumerMessageRef ref=new ConsumerMessageRef();
                ref.setAckEntry(entry);
                ref.setMessageEntry(tsa.getMessageEntry());
                container.add(ref);
            }
        }
    }

    public synchronized void deleteSubscription(String clientId,String subscriptionName){
        String key=getSubscriptionKey(clientId,subscriptionName);
        subscriberContainer.remove(key);
        TopicSubContainer container=(TopicSubContainer)subscriberMessages.get(key);
        for(Iterator i=container.getListContainer().iterator();i.hasNext();){
            ConsumerMessageRef ref=(ConsumerMessageRef)i.next();
            if(ref!=null){
                TopicSubAck tsa=(TopicSubAck)ackContainer.get(ref.getAckEntry());
                if(tsa!=null){
                    if(tsa.decrementCount()<=0){
                        ackContainer.remove(ref.getAckEntry());
                        messageContainer.remove(tsa.getMessageEntry());
                    }else{
                        ackContainer.update(ref.getAckEntry(),tsa);
                    }
                }
            }
        }
    }

    public void recoverSubscription(String clientId,String subscriptionName,MessageRecoveryListener listener)
            throws Exception{
        String key=getSubscriptionKey(clientId,subscriptionName);
        TopicSubContainer container=(TopicSubContainer)subscriberMessages.get(key);
        if(container!=null){
            for(Iterator i=container.getListContainer().iterator();i.hasNext();){
                ConsumerMessageRef ref=(ConsumerMessageRef)i.next();
                RapidMessageReference messageReference=(RapidMessageReference)messageContainer.get(ref
                        .getMessageEntry());
                if(messageReference!=null){
                    Message m=(Message)peristenceAdapter.readCommand(messageReference.getLocation());
                    listener.recoverMessage(m);
                }
            }
        }
        listener.finished();
    }

    public void recoverNextMessages(String clientId,String subscriptionName,int maxReturned,
            MessageRecoveryListener listener) throws Exception{
        String key=getSubscriptionKey(clientId,subscriptionName);
        TopicSubContainer container=(TopicSubContainer)subscriberMessages.get(key);
        if(container!=null){
            int count=0;
            StoreEntry entry=container.getBatchEntry();
            if(entry==null){
                entry=container.getListContainer().getFirst();
            }else{
                entry=container.getListContainer().refresh(entry);
                entry=container.getListContainer().getNext(entry);
            }
            if(entry!=null){
                do{
                    ConsumerMessageRef consumerRef=(ConsumerMessageRef)container.getListContainer().get(entry);
                    RapidMessageReference messageReference=(RapidMessageReference)messageContainer.get(consumerRef
                            .getMessageEntry());
                    if(messageReference!=null){
                        Message m=(Message)peristenceAdapter.readCommand(messageReference.getLocation());
                        listener.recoverMessage(m);
                        count++;
                    }
                    container.setBatchEntry(entry);
                    entry=container.getListContainer().getNext(entry);
                }while(entry!=null&&count<maxReturned && listener.hasSpace());
            }
        }
        listener.finished();
    }

    
    public SubscriptionInfo[] getAllSubscriptions() throws IOException{
        return (SubscriptionInfo[])subscriberContainer.values().toArray(
                new SubscriptionInfo[subscriberContainer.size()]);
    }

    protected String getSubscriptionKey(String clientId,String subscriberName){
        String result=clientId+":";
        result+=subscriberName!=null?subscriberName:"NOT_SET";
        return result;
    }

    protected ListContainer addSubscriberMessageContainer(Object key) throws IOException{
        ListContainer container=store.getListContainer(key,"topic-subs");
        Marshaller marshaller=new ConsumerMessageRefMarshaller();
        container.setMarshaller(marshaller);
        TopicSubContainer tsc=new TopicSubContainer(container);
        subscriberMessages.put(key,tsc);
        return container;
    }

    public int getMessageCount(String clientId,String subscriberName) throws IOException{
        String key=getSubscriptionKey(clientId,subscriberName);
        TopicSubContainer container=(TopicSubContainer)subscriberMessages.get(key);
        return container.getListContainer().size();
    }

    /**
     * @param context
     * @param messageId
     * @param expirationTime
     * @param messageRef
     * @throws IOException
     * @see org.apache.activemq.store.MessageStore#addMessageReference(org.apache.activemq.broker.ConnectionContext,
     *      org.apache.activemq.command.MessageId, long, java.lang.String)
     */
    public void addMessageReference(ConnectionContext context,MessageId messageId,long expirationTime,String messageRef)
            throws IOException{
       throw new IOException("Not supported");
    }

       

    /**
     * @param identity
     * @return String
     * @throws IOException
     * @see org.apache.activemq.store.MessageStore#getMessageReference(org.apache.activemq.command.MessageId)
     */
    public String getMessageReference(MessageId identity) throws IOException{
        return null;
    }

   

    /**
     * @param context
     * @throws IOException
     * @see org.apache.activemq.store.MessageStore#removeAllMessages(org.apache.activemq.broker.ConnectionContext)
     */
    public synchronized void removeAllMessages(ConnectionContext context) throws IOException{
        messageContainer.clear();
        ackContainer.clear();
        for(Iterator i=subscriberMessages.values().iterator();i.hasNext();){
            TopicSubContainer container=(TopicSubContainer)i.next();
            container.getListContainer().clear();
        }
    }

    
    public synchronized void resetBatching(String clientId,String subscriptionName){
        String key=getSubscriptionKey(clientId,subscriptionName);
        TopicSubContainer topicSubContainer=(TopicSubContainer)subscriberMessages.get(key);
        if(topicSubContainer!=null){
            topicSubContainer.reset();
        }
    }

   
    public Location checkpoint() throws IOException{
       return null;
    }


    public synchronized void replayAcknowledge(ConnectionContext context,String clientId,String subscriptionName,MessageId messageId){
        String subcriberId=getSubscriptionKey(clientId,subscriptionName);
        TopicSubContainer container=(TopicSubContainer)subscriberMessages.get(subcriberId);
        if(container!=null){
            ConsumerMessageRef ref=(ConsumerMessageRef)container.getListContainer().removeFirst();
            if(ref!=null){
                TopicSubAck tsa=(TopicSubAck)ackContainer.get(ref.getAckEntry());
                if(tsa!=null){
                    if(tsa.decrementCount()<=0){
                        ackContainer.remove(ref.getAckEntry());
                        messageContainer.remove(tsa.getMessageEntry());
                    }else{
                        ackContainer.update(ref.getAckEntry(),tsa);
                    }
                }
            }
        }
    }
}









