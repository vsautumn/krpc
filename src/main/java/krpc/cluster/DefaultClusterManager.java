package krpc.cluster;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.Message;

import krpc.core.ClusterManager;
import krpc.core.InitClose;
import krpc.core.ReflectionUtils;
import krpc.core.RegistryManagerCallback;
import krpc.core.RpcClosure;
import krpc.core.TransportChannel;

public class DefaultClusterManager implements ClusterManager, RegistryManagerCallback, InitClose {
	
	static Logger log = LoggerFactory.getLogger(DefaultClusterManager.class);

	int waitMillis = 500;
	int connections = 1;
	TransportChannel transportChannel;

	HashMap<Integer,LoadBalance> lbPolicies = new HashMap<Integer,LoadBalance>(); // serviceId->policy
		
	HashSet<String> lastAddrs = new HashSet<String>();
	HashMap<Integer,ServiceInfo> serviceMap = new HashMap<Integer,ServiceInfo>();
	ConcurrentHashMap<String,AddrInfo> addrMap = new ConcurrentHashMap<String,AddrInfo>();

	public DefaultClusterManager(TransportChannel transportChannel) {
		this.transportChannel = transportChannel;
	}
	
	public void init() {
		if( connections <= 0 || connections > AddrInfo.MAX_CONNECTIONS ) 
			throw new RuntimeException("connections is not allowed");
	}
	
	public void close() {

	}
	
	public void routeChanged(String rules) {
		
	}

    public void addrChanged(Map<Integer,String> addrsMap) { 
    	doAdd(addrsMap);
    	doRemove(addrsMap);
    }

    void doAdd(Map<Integer,String> addrsMap) { 
    	boolean hasNewAddr = false;
		for(Map.Entry<Integer, String> en: addrsMap.entrySet()) {
			boolean ok = doAdd(en.getKey(),en.getValue());
			if( ok ) hasNewAddr = true;
		}
		if( hasNewAddr ) {
    		try { 
    			Thread.sleep(waitMillis); // wait for connection established
    		} catch(Exception e) {
    		}
    	}
	}


   	boolean doAdd(int serviceId,String addrs) { 

    	ServiceInfo si = serviceMap.get(serviceId);
    	if( si == null) {
    		si = new ServiceInfo(serviceId,getLbPolicy(serviceId));
    		serviceMap.put(serviceId,si);
    	}
    	HashSet<String> newSet = splitAddrs(addrs);
    	HashSet<String> toBeAdded = si.mergeFrom(newSet);
    	
    	boolean hasNewAddr = false;
    	for(String addr: toBeAdded) {
    		AddrInfo ai = addrMap.get(addr);
    		if( ai == null ) {
    			ai = new AddrInfo(addr,connections);
    			addrMap.put(addr, ai);
    			for(int i=0;i<connections;++i) {
            		String connId = makeConnId(addr,i);
        			transportChannel.connect(connId, addr);
    			}
    			hasNewAddr = true;
    		} 
    	}
    	
    	return hasNewAddr;
    }

   	void doRemove(Map<Integer,String> addrsMap) { 
    	
    	HashSet<String> newAddrs = new HashSet<String>();
    	for(String s:addrsMap.values() ) {
    		newAddrs.addAll( splitAddrs(s) );
    	}

    	HashSet<String> allAddrs = new HashSet<String>();
    	for( ServiceInfo si: serviceMap.values() ) {
    		si.copyTo(allAddrs);
    	}
    	
    	// set removeFlag or removeConn
    	HashSet<String> toBeRemoved = sub(allAddrs,newAddrs);  // not used by any service
    	for(String addr:toBeRemoved) {
    		AddrInfo ai = addrMap.get(addr);
    		if( ai != null ) {
    			if( ai.isConnected() ) {
    				ai.setRemoveFlag(true);
    			} else {
    				removeAddr(ai);
    			}
    		}    		
    	}
    	
    	// recover removeFlag if set by last time
    	HashSet<String> toBeRecover = sub(newAddrs,lastAddrs);
    	for(String addr:toBeRecover) {
    		AddrInfo ai = addrMap.get(addr);
    		if( ai != null ) {
    			if( ai.getRemoveFlag() ) {
    				ai.setRemoveFlag(false);
    			} 
    		}    		
    	}
    	
    	lastAddrs = newAddrs;	
    }

    public String nextConnId(int serviceId,int msgId,Message req,String excludeConnIds) {    	
    	ServiceInfo si = serviceMap.get(serviceId);
    	if( si == null ) return null;
    	AddrInfo ai = si.nextAddr(msgId,req,excludeConnIds); // msgId not used now
    	if( ai == null ) return null;
    	int index = ai.nextConnection();
    	return makeConnId(ai.addr,index);
    }

    public int nextSequence(String connId) {
    	String addr = getAddr(connId);
    	AddrInfo ai = addrMap.get(addr);
    	if( ai == null ) return 0;
    	return ai.nextSequence();
    }
    
    public boolean isConnected(String connId) {
    	String addr = getAddr(connId);
    	AddrInfo ai = addrMap.get(addr);
    	if( ai == null ) return false;
    	int index = getIndex(connId);
    	return ai.isConnected(index);
    }
    
    public void connected(String connId,String localAddr) {
    	String addr = getAddr(connId);
    	AddrInfo ai = addrMap.get(addr);
    	if( ai == null ) return;
    	int index = getIndex(connId);
    	ai.setConnected(index);
    	updateAliveConn(ai,true);
    }
    
    public void disconnected(String connId) {
    	String addr = getAddr(connId);
    	AddrInfo ai = addrMap.get(addr);
    	if( ai == null ) return;
    	int index = getIndex(connId);
    	ai.setDisConnected(index);
    	boolean connected = ai.isConnected();
    	if( !connected && ai.getRemoveFlag() ) {
    		removeAddr(ai);
    		return;
    	}
    	updateAliveConn(ai,connected);
    }

    void removeAddr(AddrInfo ai) {
    	for(int i=0;i<ai.connections;++i)
    		transportChannel.disconnect(makeConnId(ai.addr,i));
    	addrMap.remove(ai.addr);
    	for( ServiceInfo si: serviceMap.values() ) {
			si.remove(ai);
    	}    	
    }
    
    void updateAliveConn(AddrInfo ai,boolean connected) {  	
    	for( ServiceInfo si: serviceMap.values() ) {
    		si.updateAliveConn(ai, connected);
    	}
    }
    
    public void updateStats(RpcClosure closure) {
    	LoadBalance lbPolicy = getLbPolicy(closure.getCtx().getMeta().getServiceId());
    	if( lbPolicy == null || !lbPolicy.needCallStats() ) return;
    	String connId = closure.getCtx().getConnId();
    	String addr = getAddr(connId);
    	AddrInfo ai = addrMap.get(addr);
    	if( ai == null ) return;    	
    	int retCode = ReflectionUtils.getRetCode(closure.getRes());
    	long ts = closure.getCtx().timeMillisUsed();
    	ai.updateResult(retCode, ts);
    }

	String getAddr(String connId) {
		int p = connId.lastIndexOf(":");
		return connId.substring(0,p);
	}
	
	int getIndex(String connId) {
		int p = connId.lastIndexOf(":");
		return Integer.parseInt(connId.substring(p+1)) - 1;
	}
	
	String makeConnId(String addr,int index) {
		return addr+":"+(index+1);
	}

	HashSet<String> splitAddrs(String s) {
	    HashSet<String> newSet = new HashSet<String>();
		
		if( s != null && s.length() >  0 ) {
			String[] ss =  s.split(",");
			for(String t: ss) newSet.add(t);
		}
		
		return newSet;
	}

	public HashSet<String> sub(HashSet<String> a,HashSet<String> b) {
		HashSet<String> result = new HashSet<String>();
		result.addAll(a);
		result.removeAll(b);    	
		return result;
	}
	
    public void addLbPolicy(int serviceId,LoadBalance policy) {
    	lbPolicies.put(serviceId,policy);
    }
    
    LoadBalance getLbPolicy(int serviceId) {
    	LoadBalance p = lbPolicies.get(serviceId);
    	return p;
    }
    
	public TransportChannel getTransportChannel() {
		return transportChannel;
	}

	public void setTransportChannel(TransportChannel transportChannel) {
		this.transportChannel = transportChannel;
	}

	public int getWaitMillis() {
		return waitMillis;
	}

	public void setWaitMillis(int waitMillis) {
		this.waitMillis = waitMillis;
	}

	public int getConnections() {
		return connections;
	}

	public void setConnections(int connections) {
		this.connections = connections;
	}

}
