/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.test.locking;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

import org.hibernate.LockMode;
import org.hibernate.LockOptions;
import org.hibernate.PessimisticLockException;
import org.hibernate.Session;
import org.hibernate.dialect.MySQLDialect;
import org.hibernate.dialect.SQLServerDialect;
import org.hibernate.dialect.SybaseASE15Dialect;
import org.hibernate.exception.GenericJDBCException;
import org.hibernate.exception.LockAcquisitionException;

import org.hibernate.testing.SkipForDialect;
import org.hibernate.testing.TestForIssue;
import org.hibernate.testing.async.Executable;
import org.hibernate.testing.async.TimedExecutor;
import org.hibernate.testing.junit4.BaseCoreFunctionalTestCase;
import org.junit.Test;

import static org.hibernate.testing.transaction.TransactionUtil.doInHibernate;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

/**
 * Make sure that directly specifying lock modes, even though deprecated, continues to work until removed.
 *
 * @author Steve Ebersole
 */
@TestForIssue( jiraKey = "HHH-5275")
@SkipForDialect(value=SybaseASE15Dialect.class, strictMatching=true,
		comment = "skip this test on Sybase ASE 15.5, but run it on 15.7, see HHH-6820")
public class LockModeTest extends BaseCoreFunctionalTestCase {

	private Long id;

	private CountDownLatch endLatch = new CountDownLatch( 1 );

	@Override
	protected Class<?>[] getAnnotatedClasses() {
		return  new Class[] { A.class };
	}

	@Override
	public void prepareTest() throws Exception {
		doInHibernate( this::sessionFactory, session -> {
			id = (Long) session.save( new A( "it" ) );
		} );
	}
	@Override
	protected boolean isCleanupTestDataRequired(){return true;}

	@Test
	@SuppressWarnings( {"deprecation"})
	public void testLoading() {
		// open a session, begin a transaction and lock row
		doInHibernate( this::sessionFactory, session -> {
			A it = session.byId( A.class ).with( LockOptions.UPGRADE ).load( id );
			// make sure we got it
			assertNotNull( it );

			// that initial transaction is still active and so the lock should still be held.
			// Lets open another session/transaction and verify that we cannot update the row
			nowAttemptToUpdateRow();
		} );
	}

	@Test
	public void testLegacyCriteria() {
		// open a session, begin a transaction and lock row
		doInHibernate( this::sessionFactory, session -> {

			A it = (A) session.createCriteria( A.class )
					.setLockMode( LockMode.PESSIMISTIC_WRITE )
					.uniqueResult();
			// make sure we got it
			assertNotNull( it );

			// that initial transaction is still active and so the lock should still be held.
			// Lets open another session/transaction and verify that we cannot update the row
			nowAttemptToUpdateRow();
		} );
	}

	@Test
	public void testLegacyCriteriaAliasSpecific() {
		// open a session, begin a transaction and lock row
		doInHibernate( this::sessionFactory, session -> {
			A it = (A) session.createCriteria( A.class )
					.setLockMode( "this", LockMode.PESSIMISTIC_WRITE )
					.uniqueResult();
			// make sure we got it
			assertNotNull( it );

			// that initial transaction is still active and so the lock should still be held.
			// Lets open another session/transaction and verify that we cannot update the row
			nowAttemptToUpdateRow();
		} );
	}

	@Test
	public void testQuery() {
		// open a session, begin a transaction and lock row
		doInHibernate( this::sessionFactory, session -> {
			A it = (A) session.createQuery( "from A a" )
					.setLockMode( "a", LockMode.PESSIMISTIC_WRITE )
					.uniqueResult();
			// make sure we got it
			assertNotNull( it );

			// that initial transaction is still active and so the lock should still be held.
			// Lets open another session/transaction and verify that we cannot update the row
			nowAttemptToUpdateRow();
		} );
	}

	@Test
	public void testQueryUsingLockOptions() {
		// todo : need an association here to make sure the alias-specific lock modes are applied correctly
		doInHibernate( this::sessionFactory, session -> {
			session.createQuery( "from A a" )
					.setLockOptions( new LockOptions( LockMode.PESSIMISTIC_WRITE ) )
					.uniqueResult();
			session.createQuery( "from A a" )
					.setLockOptions( new LockOptions().setAliasSpecificLockMode( "a", LockMode.PESSIMISTIC_WRITE ) )
					.uniqueResult();
		} );
	}

	private void nowAttemptToUpdateRow() {
		// here we just need to open a new connection (database session and transaction) and make sure that
		// we are not allowed to acquire exclusive locks to that row and/or write to that row.  That may take
		// one of two forms:
		//		1) either the get-with-lock or the update fails immediately with a sql error
		//		2) either the get-with-lock or the update blocks indefinitely (in real world, it would block
		//			until the txn in the calling method completed.
		// To be able to cater to the second type, we run this block in a separate thread to be able to "time it out"

		executeSync( () -> {
			doInHibernate( this::sessionFactory, _session -> {
				_session.doWork( connection -> {
					try {
						connection.setNetworkTimeout( Executors.newSingleThreadExecutor(), 1000);
					} catch (Throwable ignore) {}
				} );
				try {
					// load with write lock to deal with databases that block (wait indefinitely) direct attempts
					// to write a locked row
					A it = _session.get(
							A.class,
							id,
							new LockOptions( LockMode.PESSIMISTIC_WRITE ).setTimeOut( LockOptions.NO_WAIT )
					);
					it.setValue( "changed" );
					_session.flush();
					fail( "Pessimistic lock not obtained/held" );
				}
				catch ( Exception e ) {
					// grr, exception can be any number of types based on database
					// 		see HHH-6887
					if ( LockAcquisitionException.class.isInstance( e )
							|| GenericJDBCException.class.isInstance( e )
							|| PessimisticLockException.class.isInstance( e ) ) {
						// "ok"
					}
					else {
						fail( "Unexpected error type testing pessimistic locking : " + e.getClass().getName() );
					}
				}
			} );
		} );
	}
}
