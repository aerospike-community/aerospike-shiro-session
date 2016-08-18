/* 
 * Copyright 2012-2016 Aerospike, Inc.
 *
 * Portions may be licensed to Aerospike, Inc. under one or more contributor
 * license agreements.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.aerospike.shiro;

import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.shiro.ShiroException;
import org.apache.shiro.session.Session;
import org.apache.shiro.session.UnknownSessionException;
import org.apache.shiro.session.mgt.eis.CachingSessionDAO;
import org.apache.shiro.util.Destroyable;
import org.apache.shiro.util.Initializable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.AerospikeException;
import com.aerospike.client.Bin;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.aerospike.client.ScanCallback;
import com.aerospike.client.policy.ClientPolicy;
import com.aerospike.client.policy.Priority;
import com.aerospike.client.policy.ScanPolicy;
import com.aerospike.client.policy.WritePolicy;

public class AerospikeSessionDAO extends CachingSessionDAO implements Destroyable, Initializable {
	// Read from these system properties at init time if they exist
	@SuppressWarnings("serial")
	private static final Map<String, String> PROPERTY_MAP = Collections.unmodifiableMap(
	    new HashMap<String, String>() {{
	        put("AEROSPIKE_SESSION_NAMESPACE",		"setNamespace");
	        put("AEROSPIKE_SESSION_SETNAME", 		"setSetname");
	        put("AEROSPIKE_SESSION_BINNAME",		"setBinname");
	        put("AEROSPIKE_SESSION_HOSTNAME",		"setHostname");
	        put("AEROSPIKE_SESSION_PORT",			"setPort");
	        put("AEROSPIKE_SESSION_GLOBAL_TIMEOUT", "setGlobalSessionTimeout");
	        put("AEROSPIKE_SESSION_USER",			"setUser");
	        put("AEROSPIKE_SESSION_PASSWORD",		"setPassword");
	    }}
	);
			
	// Overwrite these default values using the shiro.ini file or via system properties
	private String namespace			= "test";
	private String setname				= "sessions";
	private String binname				= "data";
	private String hostname				= "localhost";
	private String user					= null;
	private String password				= null;
	private int port					= 3000;
	private int globalSessionTimeout	= 1800;

	private AerospikeClient client;
	private WritePolicy writePolicy;
	
	private static final transient Logger log = LoggerFactory.getLogger(AerospikeSessionDAO.class);
	
	public void setNamespace(String namespace) {
		this.namespace = namespace;
	}
	
	public void setSetname(String setname) {
		this.setname = setname;
	}
	
	public void setBinname(String binname) {
		this.binname = binname;
	}
	
	public void setUser(String user) {
		this.user = user;
	}
	
	public void setPassword(String password) {
		this.password = password;
	}
	
	public void setGlobalSessionTimeout(long timeout) {
		// Aerospike TTL is in seconds, Shiro timeout is in milliseconds
		this.globalSessionTimeout = (int)(timeout / 1000);
		log.info("Aerospike DAO session timeout: " + this.globalSessionTimeout + " seconds.");
	}
	
	public void setHostname(String hostname) {
		this.hostname = hostname;
	}
	
	public void setPort(int port) {
		this.port = port;
	}
	
	@SuppressWarnings("unused")
	private void setPort(String port) {
		this.setPort(Integer.valueOf(port));
	}
	
	@SuppressWarnings("unused")
	private void setGlobalSessionTimeout(String timeout) {
		this.setGlobalSessionTimeout(Long.valueOf(timeout));
	}
	
	@Override
	public void init() throws ShiroException {
		log.info("Initializing the Aerospike Client");
		try {
			this.readEnvironmentVariables();
		} catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException
				| SecurityException e) {		
			log.error(e.getMessage());
			throw new ShiroException(e.getMessage());
		}
		ClientPolicy policy = new ClientPolicy();
		policy.failIfNotConnected = true;
		policy.user = this.user;
		policy.password = this.password;
		this.client = new AerospikeClient(policy, this.hostname, this.port);
		this.writePolicy = new WritePolicy();
		this.writePolicy.expiration = this.globalSessionTimeout;
	}
	
	@Override
	public void destroy() throws Exception {
		log.info("Destroying the Aerospike Client");
		this.client.close();
	}
	
	@Override
	public Serializable doCreate(Session session) {
		String id =  UUID.randomUUID().toString();
		log.info("Creating session " + id);
		assignSessionId(session, id);
		
		this.storeSession(id, session);
		return id;
	}

	@Override
	public void doDelete(Session session) {
		log.info("Deleting session " + session.getId());
		Key key = new Key(this.namespace, this.setname, (String)session.getId());
		this.client.delete(null, key);
	}

	@Override
	public Session doReadSession(Serializable sessionId) throws UnknownSessionException {
		Session session = null;
		Key key = new Key(this.namespace, this.setname, (String)sessionId);
		Record rec = this.client.get(null, key);
		if (rec != null) {
			session = (Session)rec.getValue(this.binname);
			this.client.touch(this.writePolicy, key);
		} else {
			throw new UnknownSessionException();
		}
		
		return session;
	}

	@Override
	public void doUpdate(Session session) throws UnknownSessionException {
		Key key = new Key(this.namespace, this.setname, (String)session.getId());
		Record rec = this.client.get(null, key);
		if (rec != null) {
			this.storeSession((String)session.getId(), session);
		} else {
			throw new UnknownSessionException();
		}
	}

	@Override
	public Collection<Session> getActiveSessions() {
		log.info("Getting all active sessions");
		Set<Session> sessions = new HashSet<Session>();
		
		try {
			ScanPolicy policy = new ScanPolicy();
			policy.concurrentNodes = true;
			policy.priority = Priority.LOW;
			policy.includeBinData = true;
			
			class SessionScanner implements ScanCallback {
				private String binname;
				private Set<Session> sessions;
				
				public SessionScanner(String binname, Set<Session> sessions) {
					this.binname = binname;
					this.sessions = sessions;
				}
				
				@Override
				public void scanCallback(Key key, Record record) throws AerospikeException {
					this.sessions.add((Session)record.getValue(this.binname));
				}
			}

			this.client.scanAll(policy, this.namespace, this.setname, 
					new SessionScanner(this.binname, sessions), this.binname);
		} catch (AerospikeException e) {
			log.error(e.getMessage());
		}

		return sessions;
	}

	private void storeSession(String id, Session session) {
		Key key = new Key(this.namespace, this.setname, id);
		Bin bin = new Bin(this.binname, session);
		this.client.put(this.writePolicy, key , bin);
	}
	
	private void readEnvironmentVariables() throws IllegalAccessException, IllegalArgumentException,
			InvocationTargetException, NoSuchMethodException, SecurityException {
		@SuppressWarnings("rawtypes")
		Class[] paramString = new Class[1];
		paramString[0] = String.class;
		
		for (String key : PROPERTY_MAP.keySet()) {
			if (System.getProperty(key) != null) {
				Method method = this.getClass().getDeclaredMethod(PROPERTY_MAP.get(key), paramString);
				method.invoke(this, System.getProperty(key));
			}
		}
	}
}
