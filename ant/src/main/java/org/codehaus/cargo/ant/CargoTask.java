/* 
 * ========================================================================
 * 
 * Copyright 2004-2006 Vincent Massol.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 * ========================================================================
 */
package org.codehaus.cargo.ant;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Task;
import org.apache.tools.ant.types.Environment;
import org.apache.tools.ant.types.Environment.Variable;
import org.apache.tools.ant.types.Path;
import org.apache.tools.ant.types.Reference;
import org.codehaus.cargo.container.Container;
import org.codehaus.cargo.container.ContainerType;
import org.codehaus.cargo.container.LocalContainer;
import org.codehaus.cargo.container.RemoteContainer;
import org.codehaus.cargo.container.InstalledLocalContainer;
import org.codehaus.cargo.container.spi.util.ContainerUtils;
import org.codehaus.cargo.container.installer.ZipURLInstaller;
import org.codehaus.cargo.generic.ContainerFactory;
import org.codehaus.cargo.generic.DefaultContainerFactory;
import org.codehaus.cargo.util.log.Logger;
import org.codehaus.cargo.util.log.FileLogger;
import org.codehaus.cargo.util.log.AntLogger;
import org.codehaus.cargo.util.log.LogLevel;

import java.io.File;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * Ant task wrapper around the Cargo API to start/stop containers. This task is generic and can be 
 * used with any container implementation that implements the {@link Container} interface (either 
 * the implementations provided by Cargo or your own ones). 
 *
 * @version $Id$
 */
public class CargoTask extends Task
{
    /**
     * Represents a start container action.
     * @see #setAction(String)
     */
    private static final String ACTION_START = "start";

    /**
     * Represents a stop container action.
     * @see #setAction(String)
     */
    private static final String ACTION_STOP = "stop";

    /**
     * The action that will be executed by this task.
     * @see #setAction(String)
     */
    private String action;

    /**
     * If specified, the task will create an Ant property pointing to the container instance
     * created by the task.
     */
    private String id;

    /**
     * If specified, allows to reuse a previously created container instance.
     */
    private Reference refid;

    /**
     * An id representing the container (unique per container type).
     */
    private String containerId;

    /**
     * The container's type (e.g. "installed", "embedded" and "remote").
     */
    private ContainerType containerType = ContainerType.INSTALLED;

    /**
     * List of system properties to set in the container JVM. 
     */
    private Map systemProperties = new HashMap();

    /**
     * Additional classpath entries for the classpath that will be used to start the containers.
     */
    private Path extraClasspath;

    /**
     * Classpath entries for the classpath that will be shared the applications
     * deployed in a container.
     */
    private Path sharedClasspath;

    /**
     * The file to which output of the container should be written. If not specified the output
     * will be logged in Cargo's log file.
     */
    private String output;

    /**
     * Cargo log file. If no file is specified the output will be logged on stdout.
     */
    private File log;

    /**
     * Logging level. Valid values are "warn", "info" and "debug". A logging level of warn only
     * logs warnings. A level of info logs both warnings and info messages. A level of debug logs
     * all 3 levels of messages.
     */
    private LogLevel logLevel;

    /**
     * Whether output of the container should be appended to an existing file, or the existing file
     * should be truncated.
     */
    private boolean append;

    /**
     * The container home.
     */
    private String home;

    /**
     * Timeout for starting/stopping the container.
     */
    private long timeout;

    /**
     * ZipURLInstaller configuration (if defined by the user).
     */
    private ZipURLInstallerElement zipURLInstallerElement;

    /**
     * Configuration (if defined by the user). If not specified we'll use a default standalone
     * configuration.
     */
    private ConfigurationElement configurationElement;

    /**
     * Factory to create container instances from a container id.
     */
    private ContainerFactory factory = new DefaultContainerFactory();

    /**
     * Custom container implementation class to use.
     */
    private Class containerClass;

    /**
     * The container instance created by the {@link #execute()} call.
     */
    private Container container;

    /**
     * the logger to use to log outputs from the execution of the Ant task.
     */
    private Logger logger;

    /**
     * Decides whether to wait after the container is started or to return the execution
     * flow to the user.
     */
    private boolean wait = true;

    /**
     * @param wait if true wait indefinitely after the container is started, if false return the
     *        execution flow to the user
     */
    public void setWait(boolean wait)
    {
        this.wait = wait;
    }

    /**
     * @return whether the task will block execution after the container is started or not
     */
    public boolean getWait()
    {
        return this.wait;
    }

    /**
     * @param containerClass the container implementation class to use
     */
    public final void setClass(Class containerClass)
    {
        this.containerClass = containerClass;
    }

    /**
     * {@inheritDoc}
     * @see #setClass(Class)
     */
    protected final Class getContainerClass()
    {
        return this.containerClass;
    }

    /**
     * Sets the action to execute (either "start" or "stop").
     * 
     * @param action the action that will be executed by this task
     */
    public void setAction(String action)
    {
        this.action = action;
    }

    /**
     * @param containerId the container id
     */
    public void setContainerId(String containerId)
    {
        this.containerId = containerId;
    }

    /**
     * @param type the container's type (e.g. "installed", "embedded" or "remote")
     */
    public void setType(ContainerType type)
    {
        this.containerType = type;
    }

    /**
     * @param hint the container's type (e.g. "installed", "embedded" or "remote")
     * @deprecated see {@link #setType(ContainerType)}
     */
    public void setHint(String hint)
    {
        throw new BuildException("The \"hint\" attribute has been removed. You should use the "
            + "\"type\" attribute instead.");
    }

    /**
     * Sets the home dir.
     * @param home home dir to set.
     */
    public final void setHome(String home)
    {
        this.home = home;
    }

    /**
     * @param id the Ant property name that will contain the container instance reference
     */
    public final void setId(String id)
    {
        this.id = id;
    }

    /**
     * @param refid the Ant property name that contains an existing container instance reference
     */
    public final void setRefId(Reference refid)
    {
        this.refid = refid;
    }

    /**
     * @return the configured {@link ZipURLInstallerElement} element
     */
    public final ZipURLInstallerElement createZipURLInstaller()
    {
        if (getZipURLInstaller() == null)
        {
            this.zipURLInstallerElement = new ZipURLInstallerElement();
        }

        return this.zipURLInstallerElement;
    }

    /**
     * @return the configured {@link ConfigurationElement} element
     */
    public final ConfigurationElement createConfiguration()
    {
        if (getConfiguration() == null)
        {
            this.configurationElement = new ConfigurationElement();
        }

        return this.configurationElement;
    }

    /**
     * {@inheritDoc}
     * @see LocalContainer#setOutput(String)
     */
    public final void setOutput(String output)
    {
        this.output = output;
    }

    /**
     * {@inheritDoc}
     * @see org.codehaus.cargo.util.log.LoggedObject#setLogger(Logger)
     */
    public final void setLog(File log)
    {
        this.log = log;
    }

    /**
     * {@inheritDoc}
     * @see org.codehaus.cargo.util.internal.log.AbstractLogger#setLevel(org.codehaus.cargo.util.log.LogLevel)
     */
    public void setLogLevel(String logLevel)
    {
        this.logLevel = LogLevel.toLevel(logLevel);
    }

    /**
     * {@inheritDoc}
     * @see LocalContainer#setAppend(boolean)
     */
    public final void setAppend(boolean isAppend)
    {
        this.append = isAppend;
    }

    /**
     * {@inheritDoc}
     * @see LocalContainer#setTimeout(long)
     */
    public void setTimeout(long timeout)
    {
        this.timeout = timeout;
    }

    /**
     * Adds extra classpath that will be used for starting the container. 
     *
     * @return reference to the classpath
     */
    public Path createExtraClasspath()
    {
        if (getExtraClasspath() == null)
        {
            this.extraClasspath = new Path(getProject());
        }

        return this.extraClasspath.createPath();
    }

    /**
     * Adds shared classpath that will be shared by container applications.
     *
     * @return reference to the classpath
     */
    public Path createSharedClasspath()
    {
        if (getSharedClasspath() == null)
        {
            this.sharedClasspath = new Path(getProject());
        }

        return this.sharedClasspath.createPath();
    }

    /**
     * Adds a system property that will be set up in the executing container VM.
     * 
     * @param property the system property to add
     */
    public void addSysproperty(Environment.Variable property)
    {
        getSystemProperties().put(property.getKey(), property.getValue());
    }

    /**
     * Adds a set of properties that will be used as system properties in the executing container
     * VM.
     * 
     * Note: When we switch to Ant 1.6 we will be able to replace this by Ant 1.6 PropertySet
     * 
     * @param propertySet Ant element defining the property set 
     */
    public void addConfiguredSyspropertyset(PropertySet propertySet)
    {
        ResourceBundle bundle = propertySet.readProperties();
        Enumeration keys = bundle.getKeys();
        while (keys.hasMoreElements())
        {
            String key = (String) keys.nextElement();
            Variable var = new Variable();
            var.setKey(key);
            var.setValue(bundle.getString(key));
            addSysproperty(var);
        }
    }

    /**
     * Called by Ant when the Variable object has been properly initialized.
     * 
     * @param property the system property to set 
     */
    public void addConfiguredSysproperty(Environment.Variable property)
    {
        addSysproperty(property);
    }

    /**
     * Create the Cargo logger that will be used for logging all messages. If the user has
     * specified a log file we creare a File logger. If no file has been specified we use an Ant
     * logger by default to log to the Ant logging subsystem.
     */
    private void createCargoLogger()
    {
        if (getLog() != null)
        {
            this.logger = new FileLogger(getLog(), false);
        }
        else
        {
            // Use an Ant logger adapter to log to Ant
            this.logger = new AntLogger(getProject());
        }

        if (getLogLevel() != null)
        {
            this.logger.setLevel(getLogLevel());
        }
    }

    /**
     * @see Task#execute()
     */
    public void execute()
    {
        this.container = makeContainer();

        // Verify that the task is correctly set up.
        verify();

        // Setup all attributes and nested elements

        setupLogger();

        if (getContainer().getType().isLocal())
        {
            setupOutput();
            setupTimeout();

            if (getContainer().getType() == ContainerType.INSTALLED)
            {
                setupHome();
                setupExtraClasspath();
                setupSharedClasspath();
                setupSystemProperties();
            }
        }

        // Save the reference id if specified
        if (getId() != null)
        {
            getProject().addReference(getId(), getContainer());
        }

        executeActions();
    }

    /**
     * Execute the action specified by the user.
     */
    protected void executeActions()
    {
        if (getAction() != null)
        {
            if (!getContainer().getType().isLocal())
            {
                throw new BuildException("Only local containers can execute actions");
            }

            if (getAction().equalsIgnoreCase(ACTION_START))
            {
                ((LocalContainer) getContainer()).start();

                if (getWait())
                {
                    log("Press Ctrl-C to stop the container...");
                    ContainerUtils.waitTillContainerIsStopped(getContainer());
                }
            }
            else if (getAction().equalsIgnoreCase(ACTION_STOP))
            {
                ((LocalContainer) getContainer()).stop();
            }
        }
    }

    /**
     * Set up a logger for the container.
     */
    protected void setupLogger()
    {
        getContainer().setLogger(getLogger());

        if (getContainer().getType().isLocal())
        {
            ((LocalContainer) getContainer()).getConfiguration().setLogger(getLogger());
        }
        else
        {
            ((RemoteContainer) getContainer()).getConfiguration().setLogger(getLogger());
        }
    }

    /**
     * Set up an output file containing container's console output if defined.
     */
    protected void setupOutput()
    {
        if (getOutput() != null)
        {
            ((LocalContainer) getContainer()).setOutput(getOutput());
        }

        if (isAppend())
        {
            ((LocalContainer) getContainer()).setAppend(true);
        }
    }

    /**
     * Set up a timeout if defined.
     */
    protected void setupTimeout()
    {
        if (getTimeout() != 0)
        {
            if (getTimeout() != ((LocalContainer) getContainer()).getTimeout())
            {
                ((LocalContainer) getContainer()).setTimeout(getTimeout());
            }
        }
    }

    /**
     * Set up a home dir (possibly using a ZipURLInstaller).
     */
    protected void setupHome()
    {
        if (getHome() != null)
        {
            ((InstalledLocalContainer) getContainer()).setHome(getHome());
        }
        else if (getZipURLInstaller() != null)
        {
            ZipURLInstaller installer = getZipURLInstaller().createInstaller();
            installer.setLogger(getContainer().getLogger());
            installer.install();
            ((InstalledLocalContainer) getContainer()).setHome(installer.getHome());
        }
    }

    /**
     * Set up extra classpaths if defined.
     */
    protected void setupExtraClasspath()
    {
        if (getExtraClasspath() != null)
        {
            ((InstalledLocalContainer) getContainer()).setExtraClasspath(
                getExtraClasspath().list());
        }
    }

    /**
     * Set up shared classpath if defined.
     */
    protected void setupSharedClasspath()
    {
        if (getSharedClasspath() != null)
        {
            ((InstalledLocalContainer) getContainer()).setSharedClasspath(
                getSharedClasspath().list());
        }
    }

    /**
     * Set up system properties if defined.
     */
    protected void setupSystemProperties()
    {
        if (!getSystemProperties().isEmpty())
        {
            ((InstalledLocalContainer) getContainer()).setSystemProperties(getSystemProperties());
        }
    }

    /**
     * {@inheritDoc}
     * @see #addSysproperty(Environment.Variable)
     */
    protected final Map getSystemProperties()
    {
        return this.systemProperties;
    }

    /**
     * {@inheritDoc}
     * @see #createZipURLInstaller()
     */
    protected final ZipURLInstallerElement getZipURLInstaller()
    {
        return this.zipURLInstallerElement;
    }

    /**
     * {@inheritDoc}
     * @see #createConfiguration()
     */
    protected final ConfigurationElement getConfiguration()
    {
        return this.configurationElement;
    }

    /**
     * {@inheritDoc}
     * @see #createExtraClasspath()
     */
    protected final Path getExtraClasspath()
    {
        return this.extraClasspath;
    }

    /**
     * {@inheritDoc}
     * @see #createSharedClasspath()
     */
    protected final Path getSharedClasspath()
    {
        return this.sharedClasspath;
    }

    /**
     * {@inheritDoc}
     * @see #getTimeout()
     */
    protected final long getTimeout()
    {
        return this.timeout;
    }

    /**
     * {@inheritDoc}
     * @see #setOutput(String) 
     */
    protected final String getOutput()
    {
        return this.output;
    }

    /**
     * {@inheritDoc}
     * @see #setLog(File)
     */
    protected final File getLog()
    {
        return this.log;
    }

    /**
     * {@inheritDoc}
     * @see #setLogLevel(String)
     */
    protected LogLevel getLogLevel()
    {
        return this.logLevel;
    }

    /**
     * {@inheritDoc}
     * @see #setHome(String) 
     */
    protected final String getHome()
    {
        return this.home;
    }

    /**
     * {@inheritDoc}
     * @see #setAppend(boolean)
     */
    protected final boolean isAppend()
    {
        return this.append;
    }

    /**
     * @return the action to execute ("start" or "stop")
     */
    protected final String getAction()
    {
        return this.action;
    }

    /**
     * @return the container instance to start/stop
     */
    protected Container makeContainer()
    {
        Container container;
        if (getRefid() != null)
        {
            Object o = getContainerReference();
            if (!(o instanceof Container))
            {
                throw new BuildException("The [refid] attribute must point to a container "
                    + "reference, it is pointing to a [" + o.getClass().getName() + "] object");
            }
            container = (Container) o;

            // Use the logger defined in the container as the main Cargo logger
            this.logger = container.getLogger();
        }
        else
        {
            if (getConfiguration() == null)
            {
                throw new BuildException("Missing mandatory [configuration] element.");
            }

            // If the user has registered a custom container class, register it against the
            // container factory.
            if (getContainerClass() != null)
            {
                this.factory.registerContainer(this.containerId, this.containerType,
                    getContainerClass());
            }

            container = this.factory.createContainer(this.containerId, this.containerType,
                getConfiguration().createConfiguration(this.containerId, this.containerType));

            createCargoLogger();
        }

        return container;
    }

    /**
     * @return the container object refId
     */
    private Object getContainerReference()
    {
        Object o;
        try
        {
            o = getRefid().getReferencedObject(getProject());
        }
        catch (BuildException e)
        {
            throw new BuildException("The [" + getRefid().getRefId() + "] reference does not "
                + "exist. You must first define a Cargo container reference.", e);
        }
        return o;
    }

    /**
     * {@inheritDoc}
     * @see #setContainerId(String)
     */
    protected String getContainerId()
    {
        return this.containerId;
    }

    /**
     * {@inheritDoc}
     * @see #setId(String)
     */
    protected String getId()
    {
        return this.id;
    }

    /**
     * {@inheritDoc}
     * @see #setRefId(Reference)
     */
    protected Reference getRefid()
    {
        return this.refid;
    }

    /**
     * Checks if the task is correctly initialized and that the container is ready to be used.
     */
    private void verify()
    {
        if ((getId() != null) && (getRefid() != null))
        {
            throw new BuildException("You must use either [id] or [refid] but not both");
        }

        if ((getContainerId() == null) && (getRefid() == null))
        {
            throw new BuildException("You must specify a [containerId] attribute or use a [refid] "
                + "attribute");
        }

        if ((getId() == null) && (getAction() == null))
        {
            throw new BuildException("You must specify an [action] attribute with values ["
                + ACTION_START + "] or [" + ACTION_STOP + "]");
        }

        if ((getAction() != null)
            && (!getAction().equalsIgnoreCase(ACTION_START)
                && !getAction().equalsIgnoreCase(ACTION_STOP)))
        {
            throw new BuildException("Valid actions are: [" + ACTION_START + "] and ["
                + ACTION_STOP + "]");
        }

        if ((getHome() == null) && (getZipURLInstaller() == null))
        {
            boolean doFail = false;

            if (getRefid() == null)
            {
                doFail = true;
            }
            else if ((getContainer().getType() == ContainerType.INSTALLED)
                && (((InstalledLocalContainer) getContainer()).getHome() == null))
            {
                doFail = true;
            }

            if (doFail)
            {
                throw new BuildException("You must specify either a [home] attribute pointing "
                    + "to the location where the " + getContainer().getName()
                    + " is installed, or a nested [zipurlinstaller] element");
            }
        }
    }

    /**
     * @param containerFactory the new container factory to use
     */
    public void setContainerFactory(ContainerFactory containerFactory)
    {
        this.factory = containerFactory;
    }

    /**
     * @return the container instance created after the execution of {@link #execute()}
     */
    protected Container getContainer()
    {
        return this.container;
    }

    /**
     * @return the logger to use to log outputs from the execution of the Ant task
     */
    protected Logger getLogger()
    {
        return this.logger;
    }
}
