/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * Copyright (c) 2011, Red Hat Inc. or third-party contributors as
 * indicated by the @author tags or express copyright attribution
 * statements applied by the authors.  All third-party contributions are
 * distributed under license by Red Hat Inc.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, write to:
 * Free Software Foundation, Inc.
 * 51 Franklin Street, Fifth Floor
 * Boston, MA  02110-1301  USA
 */
package org.hibernate.testing.junit4;

import static org.junit.Assert.fail;

import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.hibernate.EmptyInterceptor;
import org.hibernate.HibernateException;
import org.hibernate.Interceptor;
import org.hibernate.Session;
import org.hibernate.boot.registry.BootstrapServiceRegistry;
import org.hibernate.boot.registry.internal.StandardServiceRegistryImpl;
import org.hibernate.cache.spi.access.AccessType;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.hibernate.cfg.Environment;
import org.hibernate.cfg.Mappings;
import org.hibernate.dialect.Dialect;
import org.hibernate.engine.config.spi.ConfigurationService;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.internal.util.StringHelper;
import org.hibernate.jdbc.AbstractReturningWork;
import org.hibernate.jdbc.Work;
import org.hibernate.mapping.Collection;
import org.hibernate.mapping.PersistentClass;
import org.hibernate.mapping.Property;
import org.hibernate.mapping.SimpleValue;
import org.hibernate.metamodel.MetadataBuilder;
import org.hibernate.metamodel.MetadataSources;
import org.hibernate.metamodel.SessionFactoryBuilder;
import org.hibernate.metamodel.spi.MetadataImplementor;
import org.hibernate.metamodel.spi.binding.AbstractPluralAttributeBinding;
import org.hibernate.metamodel.spi.binding.AttributeBinding;
import org.hibernate.metamodel.spi.binding.Caching;
import org.hibernate.metamodel.spi.binding.EntityBinding;
import org.hibernate.testing.AfterClassOnce;
import org.hibernate.testing.BeforeClassOnce;
import org.hibernate.testing.OnExpectedFailure;
import org.hibernate.testing.OnFailure;
import org.hibernate.testing.SkipLog;
import org.hibernate.testing.cache.CachingRegionFactory;
import org.hibernate.type.Type;
import org.junit.After;
import org.junit.Before;

/**
 * Applies functional testing logic for core Hibernate testing on top of {@link BaseUnitTestCase}
 *
 * @author Steve Ebersole
 */
public abstract class BaseCoreFunctionalTestCase extends BaseFunctionalTestCase {

	private SessionFactoryImplementor sessionFactory;

	protected Session session;

	protected SessionFactoryImplementor sessionFactory() {
		return sessionFactory;
	}

	protected Session openSession() throws HibernateException {
		session = sessionFactory().openSession();
		return session;
	}

	protected Session openSession(Interceptor interceptor) throws HibernateException {
		session = sessionFactory().withOptions().interceptor( interceptor ).openSession();
		return session;
	}


	// before/after test class ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	@BeforeClassOnce
	@SuppressWarnings( {"UnusedDeclaration"})
	protected void buildSessionFactory() {
		Properties properties = constructProperties();
		
		BootstrapServiceRegistry bootRegistry = buildBootstrapServiceRegistry();
		serviceRegistry = buildServiceRegistry( bootRegistry, properties );
		
		ConfigurationService configService = serviceRegistry.getService( ConfigurationService.class );
		isMetadataUsed = configService.getSetting(
				USE_NEW_METADATA_MAPPINGS,
				new ConfigurationService.Converter<Boolean>() {
					@Override
					public Boolean convert(Object value) {
						return Boolean.parseBoolean( ( String ) value );
					}
				},
				true
		);
		if ( isMetadataUsed ) {
			MetadataBuilder metadataBuilder = getMetadataBuilder( bootRegistry, serviceRegistry );
			configMetadataBuilder(metadataBuilder);
			metadata = (MetadataImplementor)metadataBuilder.build();
			afterConstructAndConfigureMetadata( metadata );
			applyCacheSettings( metadata );
			SessionFactoryBuilder sessionFactoryBuilder = metadata.getSessionFactoryBuilder();
			configSessionFactoryBuilder(sessionFactoryBuilder);
			sessionFactory = ( SessionFactoryImplementor )sessionFactoryBuilder.build();
		}
		else {
			configuration = constructAndConfigureConfiguration(properties);
			// this is done here because Configuration does not currently support 4.0 xsd
			afterConstructAndConfigureConfiguration( configuration );
			sessionFactory = ( SessionFactoryImplementor ) configuration.buildSessionFactory( serviceRegistry );
		}
		afterSessionFactoryBuilt();
	}

	protected void rebuildSessionFactory() {
		releaseSessionFactory();
		buildSessionFactory();
	}


	protected void afterConstructAndConfigureMetadata(MetadataImplementor metadataImplementor) {
	}

	protected void configMetadataBuilder(MetadataBuilder metadataBuilder) {
	}

	protected void configSessionFactoryBuilder(SessionFactoryBuilder sessionFactoryBuilder) {
	}

	protected void configSessionFactoryBuilder(SessionFactoryBuilder sessionFactoryBuilder, Configuration configuration) {
		if ( configuration.getEntityNotFoundDelegate() != null ) {
			sessionFactoryBuilder.with( configuration.getEntityNotFoundDelegate() );
		}
		if ( configuration.getSessionFactoryObserver() != null ){
			sessionFactoryBuilder.add( configuration.getSessionFactoryObserver() );
		}
		if ( configuration.getInterceptor() != EmptyInterceptor.INSTANCE ) {
			sessionFactoryBuilder.with( configuration.getInterceptor() );
		}
	}

	protected MetadataBuilder getMetadataBuilder(
			BootstrapServiceRegistry bootRegistry,
			StandardServiceRegistryImpl serviceRegistry) {
		MetadataSources sources = new MetadataSources( bootRegistry );
		addMappings( sources );
		return sources.getMetadataBuilder(serviceRegistry);
	}
	
	protected Configuration constructConfiguration() {
		return new Configuration().setProperties( constructProperties() );
	}

	private Configuration constructAndConfigureConfiguration(Properties properties) {
		Configuration cfg = new Configuration().setProperties( properties );
		configure( cfg );
		return cfg;
	}

	private void afterConstructAndConfigureConfiguration(Configuration cfg) {
		addMappings( cfg );
		cfg.buildMappings();
		applyCacheSettings( cfg );
		afterConfigurationBuilt( cfg );
	}

	protected Properties constructProperties() {
		Properties properties = new Properties();
		properties.put( Environment.CACHE_REGION_FACTORY, CachingRegionFactory.class.getName() );
		properties.put( AvailableSettings.USE_NEW_ID_GENERATOR_MAPPINGS, "true" );
		properties.put( Environment.DIALECT, getDialect().getClass().getName() );
		if(createSchema()){
			properties.put( AvailableSettings.HBM2DDL_AUTO, "create-drop" );
		}
		return properties;
	}

	protected void configure(Configuration configuration) {
	}

	public static void applyCacheSettings(MetadataImplementor metadataImplementor, String strategy, boolean overrideCacheStrategy){
		if( StringHelper.isEmpty(strategy)){
			return;
		}
		for( EntityBinding entityBinding : metadataImplementor.getEntityBindings()){
			boolean hasLob = false;
			for( AttributeBinding attributeBinding : entityBinding.getAttributeBindingClosure()){
				if ( attributeBinding.getAttribute().isSingular() ) {
					Type type = attributeBinding.getHibernateTypeDescriptor().getResolvedTypeMapping();
					String typeName = type.getName();
					if ( "blob".equals( typeName ) || "clob".equals( typeName ) ) {
						hasLob = true;
					}
					if ( Blob.class.getName().equals( typeName ) || Clob.class.getName().equals( typeName ) ) {
						hasLob = true;
					}
				}
			}
			if ( !hasLob && entityBinding.getSuperEntityBinding() == null && overrideCacheStrategy ) {
				Caching caching = entityBinding.getHierarchyDetails().getCaching();
				if ( caching == null ) {
					caching = new Caching();
				}
				caching.setRegion( entityBinding.getEntity().getName() );
				caching.setCacheLazyProperties( true );
				caching.setAccessType( AccessType.fromExternalName( strategy ) );
				entityBinding.getHierarchyDetails().setCaching( caching );
			}
			for( AttributeBinding attributeBinding : entityBinding.getAttributeBindingClosure()){
				if ( !attributeBinding.getAttribute().isSingular() ) {
					AbstractPluralAttributeBinding binding = AbstractPluralAttributeBinding.class.cast( attributeBinding );
					Caching caching = binding.getCaching();
					if(caching == null){
						caching = new Caching(  );
					}
					caching.setRegion( StringHelper.qualify( entityBinding.getEntity().getName() , attributeBinding.getAttribute().getName() ) );
					caching.setCacheLazyProperties( true );
					caching.setAccessType( AccessType.fromExternalName( strategy ) );
					binding.setCaching( caching );
				}
			}
		}
	}

	protected void applyCacheSettings(MetadataImplementor metadataImplementor){
		 applyCacheSettings( metadataImplementor, getCacheConcurrencyStrategy(), overrideCacheStrategy() );
	}

	protected void applyCacheSettings(Configuration configuration) {
		if ( getCacheConcurrencyStrategy() != null ) {
			Iterator itr = configuration.getClassMappings();
			while ( itr.hasNext() ) {
				PersistentClass clazz = (PersistentClass) itr.next();
				Iterator props = clazz.getPropertyClosureIterator();
				boolean hasLob = false;
				while ( props.hasNext() ) {
					Property prop = (Property) props.next();
					if ( prop.getValue().isSimpleValue() ) {
						String type = ( (SimpleValue) prop.getValue() ).getTypeName();
						if ( "blob".equals(type) || "clob".equals(type) ) {
							hasLob = true;
						}
						if ( Blob.class.getName().equals(type) || Clob.class.getName().equals(type) ) {
							hasLob = true;
						}
					}
				}
				if ( !hasLob && !clazz.isInherited() && overrideCacheStrategy() ) {
					configuration.setCacheConcurrencyStrategy( clazz.getEntityName(), getCacheConcurrencyStrategy() );
				}
			}
			itr = configuration.getCollectionMappings();
			while ( itr.hasNext() ) {
				Collection coll = (Collection) itr.next();
				configuration.setCollectionCacheConcurrencyStrategy( coll.getRole(), getCacheConcurrencyStrategy() );
			}
		}
	}

	protected boolean overrideCacheStrategy() {
		return true;
	}

	protected String getCacheConcurrencyStrategy() {
		return null;
	}

	protected void afterConfigurationBuilt(Configuration configuration) {
		afterConfigurationBuilt( configuration.createMappings(), getDialect() );
	}

	protected void afterConfigurationBuilt(Mappings mappings, Dialect dialect) {
	}



	protected void afterSessionFactoryBuilt() {
	}

	protected boolean rebuildSessionFactoryOnError() {
		return true;
	}

	@AfterClassOnce
	@SuppressWarnings( {"UnusedDeclaration"})
	private void releaseSessionFactory() {
		if ( sessionFactory == null ) {
			return;
		}
		sessionFactory.close();
		sessionFactory = null;
		configuration = null;
		metadata = null;
		if ( serviceRegistry == null ) {
			return;
		}
		serviceRegistry.destroy();
		serviceRegistry = null;
	}

	@OnFailure
	@OnExpectedFailure
	@SuppressWarnings( {"UnusedDeclaration"})
	public void onFailure() {
		if ( rebuildSessionFactoryOnError() ) {
			rebuildSessionFactory();
		}
	}

	// before/after each test ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	@Before
	public final void beforeTest() throws Exception {
		prepareTest();
	}

	protected void prepareTest() throws Exception {
	}

	@After
	public final void afterTest() throws Exception {
		if ( isCleanupTestDataRequired() ) {
			cleanupTestData();
		}
		cleanupTest();

		cleanupSession();

		assertAllDataRemoved();

	}

	protected void cleanupCache() {
		if ( sessionFactory != null ) {
			sessionFactory.getCache().evictCollectionRegions();
			sessionFactory.getCache().evictDefaultQueryRegion();
			sessionFactory.getCache().evictEntityRegions();
			sessionFactory.getCache().evictQueryRegions();
			sessionFactory.getCache().evictNaturalIdRegions();
		}
	}
	
	protected boolean isCleanupTestDataRequired() { return false; }

	protected void cleanupTestData() throws Exception {
		Session s = openSession();
		s.beginTransaction();
		s.createQuery( "delete from java.lang.Object" ).executeUpdate();
		s.getTransaction().commit();
		s.close();
	}


	private void cleanupSession() {
		if ( session != null && ! ( (SessionImplementor) session ).isClosed() ) {
			if ( session.isConnected() ) {
				session.doWork( new RollbackWork() );
			}
			session.close();
		}
		session = null;
	}

	public class RollbackWork implements Work {
		public void execute(Connection connection) throws SQLException {
			connection.rollback();
		}
	}

	protected void cleanupTest() throws Exception {
	}

	@SuppressWarnings( {"UnnecessaryBoxing", "UnnecessaryUnboxing"})
	protected void assertAllDataRemoved() {
		if ( !createSchema() ) {
			return; // no tables were created...
		}
		if ( !Boolean.getBoolean( VALIDATE_DATA_CLEANUP ) ) {
			return;
		}

		Session tmpSession = sessionFactory.openSession();
		try {
			List list = tmpSession.createQuery( "select o from java.lang.Object o" ).list();

			Map<String,Integer> items = new HashMap<String,Integer>();
			if ( !list.isEmpty() ) {
				for ( Object element : list ) {
					Integer l = items.get( tmpSession.getEntityName( element ) );
					if ( l == null ) {
						l = 0;
					}
					l = l + 1 ;
					items.put( tmpSession.getEntityName( element ), l );
					System.out.println( "Data left: " + element );
				}
				fail( "Data is left in the database: " + items.toString() );
			}
		}
		finally {
			try {
				tmpSession.close();
			}
			catch( Throwable t ) {
				// intentionally empty
			}
		}
	}

	protected boolean readCommittedIsolationMaintained(String scenario) {
		int isolation = java.sql.Connection.TRANSACTION_READ_UNCOMMITTED;
		Session testSession = null;
		try {
			testSession = openSession();
			isolation = testSession.doReturningWork(
					new AbstractReturningWork<Integer>() {
						@Override
						public Integer execute(Connection connection) throws SQLException {
							return connection.getTransactionIsolation();
						}
					}
			);
		}
		catch( Throwable ignore ) {
		}
		finally {
			if ( testSession != null ) {
				try {
					testSession.close();
				}
				catch( Throwable ignore ) {
				}
			}
		}
		if ( isolation < java.sql.Connection.TRANSACTION_READ_COMMITTED ) {
			SkipLog.reportSkip( "environment does not support at least read committed isolation", scenario );
			return false;
		}
		else {
			return true;
		}
	}
}
