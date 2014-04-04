package org.safehaus.jettyjam.utils;


import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * An external resource that start and stops the embedded Jetty application in an
 * executable jar file generated by the build for conducting integration tests with
 * the maven failsafe plugin.
 */
public class JettyIntegResource implements JettyResource {
    public static final String RESOURCE_FILE = "jettyjam.properties";

    private static final String REMOTE_DEBUG = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=";
    private static final Logger LOG = LoggerFactory.getLogger( JettyIntegResource.class );

    public static final String JAR_FILE_PATH_KEY = "jar.file.path";
    private final String jarFilePath;
    private Process process;
    private PrintWriter out;
    private BufferedReader inErr;
    private String pidFilePath;
    private Properties appProperties;
    private Properties systemProperties = new Properties();
    private String[] args = new String[0];
    private String hostname;
    private URL serverUrl;
    private boolean secure = false;
    private boolean started = false;
    private int port;
    private int debugPort = -1;


    /**
     * Creates a new JettyIntegResource.
     *
     * @throws RuntimeException if the {@link #RESOURCE_FILE} cannot be found.
     */
    public JettyIntegResource() {
        jarFilePath = findExecutableJar();
    }


    public JettyIntegResource( String[] args, Properties properties ) {
        this();
        this.args = args;
        this.systemProperties = properties;
    }


    public JettyIntegResource( String[] args ) {
        this();
        this.args = args;
    }


    public JettyIntegResource( Properties systemProperties ) {
        this();
        this.systemProperties = systemProperties;
    }


    public JettyIntegResource( int debugPort, String[] args, Properties systemProperties ) {
        this();
        this.debugPort = debugPort;
        this.args = args;
        this.systemProperties = systemProperties;
    }


    public Properties getAppProperties() {
        return appProperties;
    }


    /**
     * Finds the path to the executable jar file with the embedded Jetty application.
     * There are a number of approaches that could be taken to discover the executable
     * jar file produced by the build. The most reliable, which will work both on the
     * command line via Maven and in IDE environments is to load a properties
     * resource file containing the property for the executable jar file path. The
     * properties can have variable substitutions applied to it. The down side to this
     * approach is that every project that uses this external resource must have that
     * resource file present in order to run IT tests on the generated executable jar
     * file.
     *
     * @return the path to the executable jar
     */
    private String findExecutableJar() {
        InputStream in = getClass().getClassLoader().getResourceAsStream( RESOURCE_FILE );

        if ( in != null ) {
            Properties props = new Properties();
            try {
                props.load( in );
            }
            catch ( IOException e ) {
                return null;
            }
            return props.getProperty( JAR_FILE_PATH_KEY );
        }

        LOG.warn( "Resource file for finding the executable jar {} not found.", RESOURCE_FILE );
        return null;
    }


    protected void before() throws Exception {
        File jarFile = new File( jarFilePath );

        if ( ! jarFile.exists() ) {
            throw new FileNotFoundException( "Cannot find jar file: " + jarFile.getCanonicalPath() );
        }

        List<String> cmd = new ArrayList<String>( 4 + args.length + systemProperties.size() );
        cmd.add( "java" );
        cmd.add( "-jar" );

        if ( debugPort > 0 ) {
            cmd.add( REMOTE_DEBUG + debugPort );
        }

        for ( Object key : systemProperties.keySet() ) {
            String propName = ( String ) key;

            if ( systemProperties.getProperty( propName ) == null ) {
                cmd.add( "-D" + propName );
            }
            else {
                cmd.add( "-D" + propName  + "=" + systemProperties.getProperty( propName ) );
            }
        }

        cmd.add( jarFile.getCanonicalPath() );

        // don't know if add is append so not using Collection copy
        //noinspection ManualArrayToCollectionCopy
        for ( String arg : args ) {
            cmd.add( arg );
        }

        String[] execArgs = cmd.toArray( new String [ cmd.size() ] );
        process = Runtime.getRuntime().exec( execArgs );

        // the path to the pidFilePath will be output from the stderr stream
        inErr = new BufferedReader( new InputStreamReader( process.getErrorStream() ) );
        final BufferedReader inOut = new BufferedReader( new InputStreamReader( process.getInputStream() ) );

        new Thread( new Runnable() {
            @Override
            public void run() {
                String line;
                while ( true ) {
                    try {
                        line = inOut.readLine();
                        System.out.println( line );
                    }
                    catch ( IOException e ) {
                        if ( e.getMessage().trim().equalsIgnoreCase( "Stream closed" ) ) {
                            LOG.info( "External process output stream closed." );
                            return;
                        }

                        e.printStackTrace();
                        LOG.info( "Exception causing stoppage of external process output printing." );
                    }
                }
            }
        } ).start();

        Thread t = new Thread( new Runnable() {
            @Override
            public void run() {
                String line;
                boolean pidFileSet = false;

                try {
                    while ( true ) {
                        // this will block until we get an output line from stderr
                        line = inErr.readLine();

                        if ( ! pidFileSet ) {
                            pidFilePath = line;
                        }

                        if ( ! pidFileSet && foundPidFile() ) {
                            pidFileSet = true;
                            LOG.info( "Got pidFilePath {} from application CLI", pidFilePath );
                            return;
                        }
                        else if ( pidFileSet ) {
                            System.err.println( "Application stderr: " + line );
                        }
                    }
                }
                catch ( IOException e ) {
                    LOG.error( "Failure while reading from standard input", e );
                }

                // once we get the pidFilePath output we exit - a one time thing!
            }
        });
        t.start();

        issuePidFileCommand( t );

        if ( pidFilePath == null ) {
            out.close();
            process.destroy();
            throw new IllegalStateException( "No results found for the pidFile path." );
        }

        LOG.info( "Loading properties from pidFilePath = {}", pidFilePath );

        appProperties = new Properties();
        appProperties.load( new FileInputStream( pidFilePath ) );
        LOG.info( "Loaded properties file: {}", pidFilePath );
        appProperties.list( System.out );

        port = Integer.parseInt( appProperties.getProperty( JettyRunner.SERVER_PORT ) );
        hostname = "localhost";
        serverUrl = new URL( appProperties.getProperty( JettyRunner.SERVER_URL ) );
        secure = Boolean.parseBoolean( appProperties.getProperty( JettyRunner.IS_SECURE ) );
        started = true;
    }


    private boolean foundPidFile() {
        return pidFilePath != null && pidFilePath.startsWith( "/" ) && pidFilePath.endsWith( ".pid" );
    }


    private void issuePidFileCommand( Thread t ) throws InterruptedException {
        while ( ! foundPidFile() ) {
            // issue the command to get the pid file path from application
            out = new PrintWriter( process.getOutputStream() );
            out.println( JettyRunner.PID_FILE );
            out.flush();

            // wait until the thread above completes and we get the pidFilePath path
            t.join( 500 );
        }
    }


    protected void after() throws Exception {
        started = false;

        if ( pidFilePath != null ) {
            File pidFile = new File( pidFilePath );
            if ( pidFile.exists() && ! pidFile.delete() ) pidFile.deleteOnExit();
        }

        out.println( JettyRunner.SHUTDOWN );
        out.flush();
        out.close();
        process.destroy();
    }


    @Override
    public Statement apply( final Statement base, final Description description ) {
        return statement( base );
    }


    private Statement statement( final Statement base ) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                before();
                try {
                    base.evaluate();
                }
                finally {
                    after();
                }
            }
        };
    }


    public String getHostname() {
        return hostname;
    }


    public int getPort() {
        return port;
    }


    public URL getServerUrl() {
        return serverUrl;
    }


    public boolean isSecure() {
        return secure;
    }


    public boolean isStarted() {
        return started;
    }


    public TestParams newTestParams() {
        if ( ! started ) {
            throw new IllegalStateException( "This JettyIntegResource not started." );
        }

        return new TestParams( this );
    }


    public TestMode getMode() {
        return TestMode.INTEG;
    }
}
