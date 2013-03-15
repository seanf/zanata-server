/*
 * Copyright 2010, Red Hat, Inc. and individual contributors as indicated by the
 * @author tags. See the copyright.txt file in the distribution for a full
 * listing of individual contributors.
 * 
 * This is free software; you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 * 
 * This software is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this software; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA, or see the FSF
 * site: http://www.fsf.org.
 */
package org.zanata;

import java.io.File;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.apache.commons.io.FileUtils;
import org.dbunit.database.DatabaseConnection;
import org.dbunit.database.IDatabaseConnection;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.resteasy.client.ProxyFactory;
import org.jboss.seam.util.Naming;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ArchivePath;
import org.jboss.shrinkwrap.api.Filter;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.asset.FileAsset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.jboss.shrinkwrap.resolver.api.maven.Maven;
import org.junit.After;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.zanata.arquillian.RemoteAfter;
import org.zanata.arquillian.RemoteBefore;
import org.zanata.rest.ResourceRequestEnvironment;
import org.zanata.rest.helper.RemoteTestSignaler;

/**
 * Provides basic test utilities to test raw REST APIs and compatibility.
 *
 * @author Carlos Munoz <a href="mailto:camunoz@redhat.com">camunoz@redhat.com</a>
 */
@RunWith(Arquillian.class)
public abstract class RawRestTest extends ZanataDbunitJpaTest
{
   public static final String DEPLOYMENT_NAME = "zanata-tests";

   // Authorized environment with valid credentials
   private static final ResourceRequestEnvironment ENV_AUTHORIZED =
         new ResourceRequestEnvironment()
         {
            @Override
            public Map<String, Object> getDefaultHeaders()
            {
               return new HashMap<String, Object>()
               {
                  {
                     put("X-Auth-User", "admin");
                     put("X-Auth-Token", "b6d7044e9ee3b2447c28fb7c50d86d98");
                  }
               };
            }
         };

   @ArquillianResource
   protected URL deploymentUrl;

   @Deployment(name = "zanata.war")
   public static Archive<?> createDeployment()
   {

      WebArchive archive =  ShrinkWrap.create(WebArchive.class, DEPLOYMENT_NAME + ".war");
      archive.addAsLibraries(Maven.resolver()
            .loadPomFromFile("pom.xml")
            .importRuntimeDependencies()
            .asFile());
      // Test dependencies
      archive.addAsLibraries(Maven.resolver().loadPomFromFile("pom.xml").resolve("org.hibernate:hibernate-testing:4.1.6.Final").withoutTransitivity().asFile());
      // Missing dependencies
      // This one resolves to the gwteventservice file inside the maven resolver. Could be a bug with the alpha version
      archive.addAsLibraries(new File("/home/camunoz/.m2/repository/de/novanic/gwteventservice/eventservice/1.2.1/eventservice-1.2.1.jar"));
      // This doesn't work either...
      //archive.addAsLibraries(Maven.resolver().loadPomFromFile("pom.xml").resolve("de.novanic.gwteventservice:eventservice").withoutTransitivity().asFile());

      // Local packages
      archive.addPackages(true, new Filter<ArchivePath>()
      {
         @Override
         public boolean include(ArchivePath object)
         {
            // Avoid the model package (for some reason it's being included as a class file)
            return !object.get().startsWith("/org/zanata/model/") &&
                  // and the ui package (not needed)
                  !object.get().startsWith("/org/zanata/ui");
         }
      }, "org.zanata");

      // Resources (descriptors, etc)
      archive.addAsResource(EmptyAsset.INSTANCE, "seam.properties");
      archive.addAsWebInfResource(new File("src/test/resources/arquillian/jboss-deployment-structure.xml"));
      archive.addAsResource(new FileAsset(new File("src/main/resources/META-INF/orm.xml")), "META-INF/orm.xml");
      archive.addAsResource(new FileAsset(new File("src/test/jboss-embedded-bootstrap/META-INF/persistence.xml")), "META-INF/persistence.xml");
      archive.addAsResource(new FileAsset(new File("src/main/webapp-jboss/WEB-INF/classes/META-INF/components.xml")), "META-INF/components.xml");
      archive.addAsResource(new FileAsset(new File("src/test/resources/arquillian/components.properties")), "components.properties");
      archive.addAsResource("security.drl");
      archive.addAsWebInfResource(new File("src/test/resources/arquillian/zanata.properties"),
            "classes/zanata.properties");
      archive.addAsWebInfResource("arquillian/test-web.xml", "web.xml");

      addRemoteHelpers(archive);

      // Export (to actually see what is being deployed)
      archive.as(ZipExporter.class).exportTo(new File("/home/camunoz/temp/archive.war"), true);
      //archive.as(ZipExporter.class).exportTo(new File("/opt/jboss-eap-6.0-standalone/standalone/deployments/archive.war"), true);


      return archive;
   }

   private static void addRemoteHelpers(WebArchive archive)
   {
      archive.addPackages(true, "org.zanata.rest.helper");
      archive.addPackages(true, "org.zanata.arquillian");
      archive.addAsLibraries(Maven.resolver().loadPomFromFile("pom.xml").resolve("org.dbunit:dbunit:2.4.9").withoutTransitivity().asFile());
      addAsResources(archive, new File("src/test/resources/org/zanata/test/model"));
   }

   private static void addAsResources( WebArchive archive, File directory )
   {
      for( File file : FileUtils.listFiles(directory, null, true) )
      {
         if( !file.isDirectory() )
         {
            archive.addAsResource(file, "org/zanata/test/model/" + file.getName());
         }
      }
   }

   @RemoteBefore
   @Override
   public void prepareDataBeforeTest()
   {
      /** @see org.zanata.ZanataDbunitJpaTest#prepareDataBeforeTest()  */
      prepareDBUnitOperations();
      executeOperations(beforeTestOperations);
   }

   @RemoteAfter
   @Override
   public void cleanDataAfterTest()
   {
      /** @see ZanataDbunitJpaTest#cleanDataAfterTest()   */
      prepareDBUnitOperations();
      executeOperations(afterTestOperations);
   }

   @Before
   public void signalBeforeTest()
   {
      RemoteTestSignaler signaler = ProxyFactory.create(RemoteTestSignaler.class, getDeployedUrl("/"));
      try
      {
         signaler.signalBeforeTest(this.getClass().getName());
      }
      catch (Exception e)
      {
         e.printStackTrace();
      }
   }

   @After
   public void signalAfterTest()
   {
      RemoteTestSignaler signaler = ProxyFactory.create(RemoteTestSignaler.class, getDeployedUrl("/"));
      try
      {
         signaler.signalAfterTest(this.getClass().getName());
      }
      catch (Exception e)
      {
         e.printStackTrace();
      }
   }

   @Override
   protected IDatabaseConnection getConnection()
   {
      try
      {
         DataSource dataSource = (DataSource)Naming.getInitialContext().lookup("java:jboss/datasources/zanataTestDatasource");
         return new DatabaseConnection(dataSource.getConnection());
      }
      catch (Exception e)
      {
         throw new RuntimeException(e);
      }
   }

   /**
    * Gets the deployed url based on a relative resource url.
    *
    * @param resourceUrl The application relative resource url.
    * @return The full absolute url of the deployed resource.
    */
   public final String getDeployedUrl( String resourceUrl )
   {
      StringBuilder fullUrl = new StringBuilder(deploymentUrl.toString() + "/" + DEPLOYMENT_NAME + "/seam/resource/restv1");
      if( !resourceUrl.startsWith("/") )
      {
         fullUrl.append("/");
      }
      return fullUrl.append(resourceUrl).toString();
   }

   /**
    * Gets a valid Authorized REST environment.
    *
    * @return A Resource Request execution environment with valid test credentials.
    */
   public static final ResourceRequestEnvironment getAuthorizedEnvironment()
   {
      return ENV_AUTHORIZED;
   }

}