/*
 * Copyright 2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.data.document.mongodb;


import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.document.AbstractDocumentStoreTemplate;
import org.springframework.data.document.mongodb.query.Query;

import com.mongodb.BasicDBObject;
import com.mongodb.CommandResult;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.Mongo;
import com.mongodb.MongoException;
import com.mongodb.WriteResult;
import com.mongodb.util.JSON;

public class MongoTemplate extends AbstractDocumentStoreTemplate<DB> implements InitializingBean {

	private DB db;
	
	private String defaultCollectionName;
	
	private MongoConverter mongoConverter;
	
	//TODO expose configuration...
	private CollectionOptions defaultCollectionOptions;

	private Mongo mongo;

	private String databaseName;
	
	private String username;
	
	private char[] password;
	
	
	public MongoTemplate(Mongo mongo, String databaseName) {
		this(mongo, databaseName, null, null);
	}
	
	public MongoTemplate(Mongo mongo, String databaseName, String defaultCollectionName) {
		this(mongo, databaseName, defaultCollectionName, null);
	}
	
	public MongoTemplate(Mongo mongo, String databaseName, MongoConverter mongoConverter) {
		this(mongo, databaseName, null, mongoConverter);
	}
	
	public MongoTemplate(Mongo mongo, String databaseName, String defaultCollectionName, MongoConverter mongoConverter) {
		this.mongoConverter = mongoConverter;
		this.defaultCollectionName = defaultCollectionName;
		this.mongo = mongo;
		this.databaseName = databaseName;
	}
	
	
	/**
	 * Sets the username to use to connect to the Mongo database
	 * 
	 * @param username The username to use
	 */
	public void setUsername(String username) {
		this.username = username;
	}

	/**
	 * Sets the password to use to authenticate with the Mongo database
	 * 
	 * @param password The password to use
	 */
	public void setPassword(char[] password) {
		this.password = password;
	}

	public void setDefaultCollectionName(String defaultCollectionName) {
		this.defaultCollectionName = defaultCollectionName;
	}

	public void setDatabaseName(String databaseName) {
		this.databaseName = databaseName;
	}

	public String getDefaultCollectionName() {
		return defaultCollectionName;
	}
	
	/**
	 * @return The default collection used by this template
	 */
	public DBCollection getDefaultCollection() {
		return getConnection().getCollection(getDefaultCollectionName());
	}

	public void executeCommand(String jsonCommand) {
		executeCommand((DBObject)JSON.parse(jsonCommand));
	}

	public void executeCommand(DBObject command) {
		CommandResult cr = getConnection().command(command);
		String err = cr.getErrorMessage();
		if (err != null) {
			throw new InvalidDataAccessApiUsageException("Command execution of " + 
					command.toString() + " failed: " + err);
		}
	}
	
	/**
	 * Executes a {@link DBCallback} translating any exceptions as necessary
	 * 
	 * @param <T> The return type
	 * @param action The action to execute
	 * 
	 * @return The return value of the {@link DBCallback}
	 */
	public <T> T execute(DBCallback<T> action) {
		DB db = getConnection();

		try {
			return action.doInDB(db);
		} catch (MongoException e) {
			throw MongoDbUtils.translateMongoExceptionIfPossible(e);
		} 
	}
	
	public <T> T executeInSession(DBCallback<T> action) {
		DB db = getConnection();
		db.requestStart();
		try {
			return action.doInDB(db);
		} catch (MongoException e) {
			throw MongoDbUtils.translateMongoExceptionIfPossible(e);
		} finally {
			db.requestDone();
		}
	}
	
	public DBCollection createCollection(String collectionName) {
		try {
			return getConnection().createCollection(collectionName, null);
		} catch (MongoException e) {
			throw new InvalidDataAccessApiUsageException("Error creating collection " + collectionName + ": " + e.getMessage(), e);
		}
	}
		
	public void createCollection(String collectionName, CollectionOptions collectionOptions) {
		try {
			getConnection().createCollection(collectionName, convertToDbObject(collectionOptions));
		} catch (MongoException e) {
			throw new InvalidDataAccessApiUsageException("Error creating collection " + collectionName + ": " + e.getMessage(), e);
		}
	}
	
	public DBCollection getCollection(String collectionName) {
		try {
			return getConnection().getCollection(collectionName);
		} catch (MongoException e) {
			throw new InvalidDataAccessApiUsageException("Error creating collection " + collectionName + ": " + e.getMessage(), e);
		}
	}
		

	public boolean collectionExists(String collectionName) {
		try {
			return getConnection().collectionExists(collectionName);
		} catch (MongoException e) {
			throw new InvalidDataAccessApiUsageException("Error creating collection " + collectionName + ": " + e.getMessage(), e);
		}
	}

	public void dropCollection(String collectionName) {
		getConnection().getCollection(collectionName)
			.drop();
	}


	private String getRequiredDefaultCollectionName() {
		String name = getDefaultCollectionName();
		if (name == null) {
			throw new IllegalStateException(
					"No 'defaultCollection' or 'defaultCollectionName' specified. Check configuration of MongoTemplate.");
		}
		return name;
	}

	
	public void save(Object objectToSave) {
		save(getRequiredDefaultCollectionName(), objectToSave);
	}
	
	public void save(String collectionName, Object objectToSave) {
		BasicDBObject dbDoc = new BasicDBObject();
		this.mongoConverter.write(objectToSave, dbDoc);
		saveDBObject(collectionName, dbDoc);
	}
	
	public <T> void save(String collectionName, T objectToSave, MongoWriter<T> writer) {
		BasicDBObject dbDoc = new BasicDBObject();
		this.mongoConverter.write(objectToSave, dbDoc);
		saveDBObject(collectionName, dbDoc);
	}


	protected void saveDBObject(String collectionName, BasicDBObject dbDoc) {
		if (dbDoc.keySet().size() > 0 ) {
			WriteResult wr = null;
			try {
				wr = getConnection().getCollection(collectionName).save(dbDoc);			
			} catch (MongoException e) {
				throw new DataRetrievalFailureException(wr.getLastError().getErrorMessage(), e);
			}
		}
	}

	public <T> List<T> queryForCollection(Class<T> targetClass) {
		
		List<T> results = new ArrayList<T>();
		DBCollection collection = getConnection().getCollection(getDefaultCollectionName());
		for (DBObject dbo : collection.find()) {
			Object obj = mongoConverter.read(targetClass, dbo);
			//effectively acts as a query on the collection restricting it to elements of a specific type
			if (targetClass.isInstance(obj)) {
				results.add(targetClass.cast(obj));
			}
		}
		return results;
	}
	
	public <T> List<T> queryForCollection(String collectionName, Class<T> targetClass) {
		
		List<T> results = new ArrayList<T>();
		DBCollection collection = getConnection().getCollection(collectionName);
		for (DBObject dbo : collection.find()) {
			Object obj = mongoConverter.read(targetClass, dbo);
			//effectively acts as a query on the collection restricting it to elements of a specific type
			if (targetClass.isInstance(obj)) {
				results.add(targetClass.cast(obj));
			}
		}
		return results;
	}

	public <T> List<T> queryForCollection(String collectionName, Class<T> targetClass, MongoReader<T> reader) { 
		List<T> results = new ArrayList<T>();
		DBCollection collection = getConnection().getCollection(collectionName);
		for (DBObject dbo : collection.find()) {
			results.add(reader.read(targetClass, dbo));
		}
		return results;
	}
	
	
	public <T> List<T> queryForList(String collectionName, Query query, Class<T> targetClass) {
		return queryForList(collectionName, query.getQueryObject(), targetClass);
	}

	public <T> List<T> queryForList(String collectionName, Query query, Class<T> targetClass, MongoReader<T> reader) {
		return queryForList(collectionName, query.getQueryObject(), targetClass, reader);
	}
	
	
	

	public <T> List<T> queryForList(String collectionName, String query, Class<T> targetClass) {
		return queryForList(collectionName, (DBObject)JSON.parse(query), targetClass);
	}

	public <T> List<T> queryForList(String collectionName, String query, Class<T> targetClass, MongoReader<T> reader) {
		return queryForList(collectionName, (DBObject)JSON.parse(query), targetClass, reader);
	}

	
	//
	
	public <T> List<T> queryForList(String collectionName, DBObject query, Class<T> targetClass) {	
		DBCollection collection = getConnection().getCollection(collectionName);
		List<T> results = new ArrayList<T>();
		for (DBObject dbo : collection.find(query)) {
			Object obj = mongoConverter.read(targetClass,dbo);
			//effectively acts as a query on the collection restricting it to elements of a specific type
			if (targetClass.isInstance(obj)) {
				results.add(targetClass.cast(obj));
			}
		}
		return results;
	}

	public <T> List<T> queryForList(String collectionName, DBObject query, Class<T> targetClass, MongoReader<T> reader) {
		DBCollection collection = getConnection().getCollection(collectionName);
		List<T> results = new ArrayList<T>();
		for (DBObject dbo : collection.find(query)) {
			results.add(reader.read(targetClass, dbo));
		}
		return results;
	}

	public RuntimeException convertMongoAccessException(RuntimeException ex) {
		return MongoDbUtils.translateMongoExceptionIfPossible(ex);
	}

	@Override
	public DB getConnection() {
		if(username != null && password != null) {
			return MongoDbUtils.getDB(mongo, databaseName, username, password);
		}
		return MongoDbUtils.getDB(mongo, databaseName);
	}

	
	protected DBObject convertToDbObject(CollectionOptions collectionOptions) {
		DBObject dbo = new BasicDBObject();
		if (collectionOptions != null) {
			if (collectionOptions.getCapped() != null) {
				dbo.put("capped", collectionOptions.getCapped().booleanValue());			
			}
			if (collectionOptions.getSize() != null) {
				dbo.put("size", collectionOptions.getSize().intValue());
			}
			if (collectionOptions.getMaxDocuments() != null ) {
				dbo.put("max", collectionOptions.getMaxDocuments().intValue());
			}
		}
		return dbo;
	}



	public void afterPropertiesSet() throws Exception {
		if (this.getDefaultCollectionName() != null) {
			DB db = getConnection();
			if (! db.collectionExists(getDefaultCollectionName())) {
				db.createCollection(getDefaultCollectionName(), null);
			}
		}
		if (this.mongoConverter == null) {
			mongoConverter = new SimpleMongoConverter();
		}
		
	}
}