/*
 * Copyright 2010 Martin Grotzke
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an &quot;AS IS&quot; BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package de.javakaffee.web.msm.serializer.hibernate;

import static de.javakaffee.web.msm.integration.TestUtils.createContext;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.Serializable;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.persistence.CascadeType;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.OneToMany;

import org.apache.catalina.core.StandardContext;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.hibernate.Criteria;
import org.hibernate.Hibernate;
import org.hibernate.HibernateException;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.annotations.AccessType;
import org.hibernate.cfg.AnnotationConfiguration;
import org.hibernate.criterion.Restrictions;
import org.testng.Assert;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

import de.javakaffee.web.msm.MemcachedBackupSession;
import de.javakaffee.web.msm.MemcachedSessionService;
import de.javakaffee.web.msm.MemcachedSessionService.SessionManager;
import de.javakaffee.web.msm.SessionAttributesTranscoder;
import de.javakaffee.web.msm.TranscoderService;
import de.javakaffee.web.msm.integration.TestUtils;

/**
 * Test for serialization/deserialization of hibernate collection mappings.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public abstract class AbstractHibernateCollectionsTest {

    private static final Log LOG = LogFactory.getLog( AbstractHibernateCollectionsTest.class );

    private SessionFactory _sessionFactory;

    @BeforeTest
    protected void beforeTest() {
        _sessionFactory = new AnnotationConfiguration()
            .addAnnotatedClass( Person.class )
            .addAnnotatedClass( Animal.class )
            .configure().buildSessionFactory();
    }

    @Test( enabled = true )
    public void testDeserializeHibernateCollection() {

        final SessionManager manager = createSessionManager();
        manager.setContainer( createContext() );

        final Set<Animal> animals = new HashSet<Animal>( Arrays.asList( new Animal( "cat" ) ) );
        final Person person = new Person( "foo bar", animals );

        final Long personId = createPerson( person );
        final Person foundPerson = findPerson( personId );
        LOG.info( "person: " + person.toString() );
        LOG.info( "found: " + foundPerson.toString() );
        TestUtils.assertDeepEquals( person, foundPerson );

        final TranscoderService transcoderService = new TranscoderService( createTranscoder( manager ) );

        final MemcachedBackupSession session = createSession( manager, "123456789" );
        session.setAttribute( "person", foundPerson );

        final byte[] data = transcoderService.serialize( session );
        final MemcachedBackupSession deserialized = transcoderService.deserialize( data, manager );

        final Person deserializedPerson = (Person) deserialized.getAttribute( "person" );
        TestUtils.assertDeepEquals( foundPerson, deserializedPerson );

    }

    protected abstract SessionAttributesTranscoder createTranscoder( SessionManager manager );

    private Person findPerson( final Long personId ) {
        final Person foundPerson = withSession( new Callback<Person>() {

            @Override
            public Person execute( final Session session ) {

                final Criteria crit = session.createCriteria( Person.class ).add( Restrictions.idEq( personId ) );
                @SuppressWarnings( "unchecked" )
                final List<Person> list = crit.list();
                Assert.assertEquals( list.size(), 1 );
                final Person result = list.get( 0 );
                Hibernate.initialize( result.animals );
                return result;
            }

        });
        return foundPerson;
    }

    private Long createPerson( final Person person ) {
        final Long personId = withSession( new Callback<Long>() {

            @Override
            public Long execute( final Session session ) {
                return (Long) session.save( person );
            }

        });
        return personId;
    }

    @Entity
    @AccessType( "field" )
    @SuppressWarnings( "serial" )
    static class Person implements Serializable {

        @Id
        @GeneratedValue(strategy=GenerationType.IDENTITY)
        public Long id;
        public String name;

        @OneToMany( cascade = CascadeType.ALL )
        public Set<Animal> animals;

        public Person() {
        }
        public Person( final String name, final Set<Animal> animals ) {
            this.name = name;
            this.animals = animals;
        }

        @Override
        public String toString() {
            return "Person [id=" + id + ", name=" + name + ", animals=" + animals + "]";
        }

    }

    @Entity
    @AccessType( "field" )
    @SuppressWarnings( "serial" )
    static class Animal implements Serializable {

        @Id
        @GeneratedValue(strategy=GenerationType.IDENTITY)
        public Long id;
        public String name;

        public Animal() {
        }
        public Animal( final String name ) {
            this.name = name;
        }

        @Override
        public String toString() {
            return "Animal [id=" + id + ", name=" + name + "]";
        }

    }

    static interface Callback<T> {

        T execute(Session session);

    }

    <T> T withSession( final Callback<T> callback ) {
        final Session session = _sessionFactory.openSession();
        Transaction transaction = null;
        try {
            transaction = session.beginTransaction();
            final T result = callback.execute( session );
            transaction.commit();
            return result;
        } catch ( final HibernateException e ) {
            transaction.rollback();
            throw new RuntimeException( e );
        } finally {
            session.close();
        }
    }
    
    @Nonnull
    protected SessionManager createSessionManager() {
        final SessionManager manager = mock( SessionManager.class );
        when( manager.getContainer() ).thenReturn( new StandardContext() ); // needed for createSession
        when( manager.getMemcachedSessionService() ).thenReturn( new MemcachedSessionService( manager ) );
        when( manager.newMemcachedBackupSession() ).thenReturn( new MemcachedBackupSession( manager ) );
        return manager;
    }

    private static MemcachedBackupSession createSession( final SessionManager manager, final String id ) {
        final MemcachedBackupSession session = manager.getMemcachedSessionService().createEmptySession();
        session.setId( id );
        session.setValid( true );
        return session;
    }

}
