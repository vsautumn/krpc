package krpc.rpc.monitor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.Message;

import krpc.common.JacksonJsonConverter;
import krpc.common.JsonConverter;
import krpc.rpc.util.MessageToMap;
import krpc.rpc.web.WebMessage;

public class JacksonLogFormatter extends BaseFormatter  {
	
	static Logger log = LoggerFactory.getLogger(JacksonLogFormatter.class);

	JsonConverter jsonConverter = new JacksonJsonConverter();

	public void config(String paramsStr) {
		configInner(paramsStr);
	}

    public String toLogStr(boolean isServerLog, Message body) {
    	try {
    		Map<String,Object> allLog = new HashMap<>();
    		MessageToMap.parseMessage(body, allLog, printDefault, maxRepeatedSizeToLog);
    		adjustLog(allLog);
  	  	    return jsonConverter.toJson(allLog);
    	} catch(Exception e) {
    		log.error("toLogStr exception, e="+e.getMessage(),e);
    		return "";
    	}
	}
	
	public String toLogStr(boolean isServerLog, WebMessage body) {
		try {
			Map<String,Object> allLog = getLogData(isServerLog,body,maxRepeatedSizeToLog);
	  	    adjustLog(allLog);
	  	    return jsonConverter.toJson(allLog);
	  	} catch(Exception e) {
	  		log.error("toLogStr exception, e="+e.getMessage());
	  		return "";
	  	}
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	void adjustLog(Map<String,Object> allLog) {
		for(String key: allLog.keySet()) {
			
			if( maskFieldsSet.contains(key) ) {
				allLog.put(key,"***");
				continue;
			}
			
			Object v = allLog.get(key);
			if( v instanceof Map ) {
				adjustLog((Map)v);
				continue;
			}
			
			if( v instanceof List ) {
				List l = (List)v;
				for(Object no : l) {
					if( no instanceof Map ) {
						adjustLog((Map)no);
					}
				}
			}
		}
	}

}
