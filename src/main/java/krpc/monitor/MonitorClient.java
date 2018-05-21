package krpc.monitor;

import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import krpc.core.InitClose;
import krpc.core.RpcCodec;
import krpc.core.RpcData;
import krpc.core.ServiceMetas;
import krpc.core.StartStop;
import krpc.core.TransportCallback;
import krpc.core.proto.RpcMeta;
import krpc.impl.transport.NettyClient;
import krpc.monitor.proto.ReportRpcStatReq;
import krpc.monitor.proto.ReportRpcStatRes;

public class MonitorClient implements TransportCallback, InitClose, StartStop {

	static Logger log = LoggerFactory.getLogger(MonitorClient.class);

	static final public int MONITOR_SERVICEID = 2;
	static final public int MONITOR_REPORT_MSGID = 1;
	
	static class AddrInfo {
		String addr;
		String connId;
		AtomicBoolean connected = new AtomicBoolean(false);
		AddrInfo(String addr) { this.addr = addr; connId = addr + ":1"; }
	}
	
	int maxRetryTime = 300; // seconds
	int retryInterval = 10; // seconds
	int sendTimeout = 5; // seconds
	
	ArrayList<AddrInfo> addrList = new ArrayList<AddrInfo>();
	AtomicInteger addrIndex = new AtomicInteger(-1);
	AtomicInteger seq = new AtomicInteger(0);
	int maxLoopCount = 0;
	Timer timer;
	NettyClient nettyClient;
	
	ConcurrentHashMap<Integer,ReportRpcStatReq> dataManager = new ConcurrentHashMap<Integer,ReportRpcStatReq>();
 
	public MonitorClient(String addrs,RpcCodec codec,ServiceMetas serviceMetas,Timer timer) {
		
		serviceMetas.addDirect(MONITOR_SERVICEID,MONITOR_REPORT_MSGID,
				ReportRpcStatReq.class,ReportRpcStatRes.class);
		this.timer = timer;

		String[] ss = addrs.split(",");
		for(int i=0;i<ss.length;++i) {
			addrList.add(new AddrInfo(ss[i]));
		}
		maxLoopCount = 10000000*addrList.size();
		
		nettyClient = new NettyClient(this,codec,serviceMetas);
		nettyClient.setWorkerThreads(1);
	}

	public void init() {
	    
		nettyClient.init();
		
		for(AddrInfo ai:addrList) {
			nettyClient.connect(ai.connId, ai.addr);
		}
		
		log.info("monitor client started");
	}

	public void start() {
		nettyClient.start();
	}
	
	public void stop() {
		nettyClient.stop();
	}

	public void close() {
		for(AddrInfo ai:addrList) {
			nettyClient.disconnect(ai.connId);
		}
		nettyClient.close();

		log.info("monitor client closed");
	}

	AddrInfo findAddrInfo(String connId) {
		for(AddrInfo ai:addrList) {
			if( ai.connId.equals(connId)) return ai;
		}		
		return null;
	}

	AddrInfo nextAddrInfo() {
		for(int kk=0;kk<addrList.size();++kk) {
			int i = addrIndex.incrementAndGet();
			if( i >= maxLoopCount ) addrIndex.set(0);
			int idx = i % addrList.size();
			AddrInfo ai = addrList.get(idx);
			if( ai.connected.get() ) return ai;
		}
		return null;
	}

	public void send(ReportRpcStatReq statsReq) {
    	timer.schedule( new TimerTask() {
            public void run() {
            	doReport(statsReq);
            }
        },  0 );
	}

	void doReport(ReportRpcStatReq statsReq) {
		int sequence = nextSequence();
		dataManager.put(sequence, statsReq);
		boolean ok = send(sequence,statsReq);
		if( ok ) {
	    	timer.schedule( new TimerTask() {
	            public void run() {
	            	checkTimeout(sequence);
	            }
	        },  sendTimeout*1000 );
		} else {
			dataManager.remove(sequence);
			retry(statsReq);
		}
	}

	void retry(ReportRpcStatReq statsReq) {
		long now = System.currentTimeMillis();
		if( now - statsReq.getTimestamp() >= maxRetryTime * 1000 ) return; 
		
    	timer.schedule( new TimerTask() {
            public void run() {
            	doReport(statsReq);
            }
        },  retryInterval*1000 );
	}

	void checkTimeout(int sequence) {
		ReportRpcStatReq req = dataManager.remove(sequence);
		if( req == null ) return;
		retry(req);
	}
	
	boolean send(int sequence, ReportRpcStatReq statsReq) {
		AddrInfo ai = nextAddrInfo();
		if( ai == null ) return false;
		RpcMeta.Builder builder = RpcMeta.newBuilder().setDirection(RpcMeta.Direction.REQUEST).setServiceId(MONITOR_SERVICEID).setMsgId(MONITOR_REPORT_MSGID).setSequence(sequence);
		RpcMeta meta = builder.build();
		RpcData data = new RpcData(meta,statsReq);
		return nettyClient.send(ai.connId,data);
	}

	public void receive(String connId,RpcData data) {
		if( data.getMeta().getDirection() == RpcMeta.Direction.REQUEST ) return;
		ReportRpcStatReq req = dataManager.remove(data.getMeta().getSequence());
		if( req == null ) return;
		int retCode = data.getMeta().getRetCode();
		if( retCode == 0 ) return;
		retry(req);
    }

	public void connected(String connId,String localAddr) {
		AddrInfo ai = findAddrInfo(connId);
		if( ai == null ) return;
		ai.connected.set(true);
    }
	
	public void disconnected(String connId) {
		AddrInfo ai = findAddrInfo(connId);
		if( ai == null ) return;
		ai.connected.set(false);
    }
	
	int nextSequence() {
		int sequence = seq.incrementAndGet();
    	if( sequence >= 10000000 ) seq.set(0);
    	return sequence;
	}

}