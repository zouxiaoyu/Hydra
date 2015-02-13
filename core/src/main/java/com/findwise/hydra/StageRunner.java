package com.findwise.hydra;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecuteResultHandler;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteException;
import org.apache.commons.exec.Executor;
import org.apache.commons.exec.ProcessDestroyer;
import org.apache.commons.exec.launcher.CommandLauncher;
import org.apache.commons.exec.launcher.CommandLauncherFactory;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.findwise.hydra.stage.GroupStarter;

public class StageRunner extends Thread {

	private static final int DEFAULT_TIMES_TO_RETRY = -1;
	private static final String DEFAULT_JAVA_PATH = "java";
	private StageGroup stageGroup;
	private Logger logger = LoggerFactory.getLogger(getClass());
	private StageDestroyer stageDestroyer;
	private boolean prepared = false;
	private int timesToRetry = DEFAULT_TIMES_TO_RETRY;
	private int timesStarted;
	private int pipelinePort;

	private String jvmParameters = null;
	private String startupArgsString = null;
	private String java = DEFAULT_JAVA_PATH;

	private boolean hasQueried = false;

	private File targetDirectory;
	private File baseDirectory;

	private boolean performanceLogging = false;
	private int loggingPort;

	private boolean started;
	private boolean wasKilled = false;
	private ShutdownHandler shutdownHandler;

	public synchronized void setHasQueried() {
		hasQueried = true;
	}

	public synchronized boolean hasQueried() {
		return hasQueried;
	}

	public StageRunner(StageGroup stageGroup, File baseDirectory, int pipelinePort, boolean performanceLogging, int loggingPort, ShutdownHandler shutdownHandler, String defaultJvmParameters) {
		this.stageGroup = stageGroup;
		this.baseDirectory = baseDirectory;
		this.targetDirectory = new File(baseDirectory, stageGroup.getName());
		this.pipelinePort = pipelinePort;
		this.performanceLogging = performanceLogging;
		this.loggingPort = loggingPort;
		this.shutdownHandler = shutdownHandler;
		this.jvmParameters = defaultJvmParameters;
		timesStarted = 0;
	}

	/**
	 * This method must be called prior to a call to start()
	 *
	 * @throws IOException
	 */
	public void prepare() throws IOException {
		// Clean working directory to ensure renamed libraries are not added on classpath
		removeFiles();

		if ((!baseDirectory.isDirectory() && !baseDirectory.mkdir()) ||
				(!targetDirectory.isDirectory() && !targetDirectory.mkdir())) {
			throw new IOException("Unable to write files, target (" + targetDirectory.getAbsolutePath() + ") is not a directory");
		}

		for (DatabaseFile df : stageGroup.getDatabaseFiles()) {
			copyStageLibraryToDirectory(df, targetDirectory);
		}

		stageDestroyer = new StageDestroyer();

		setParameters(stageGroup.toPropertiesMap());
		if (stageGroup.getSize() == 1) {
			//If there is only a single stage in this group, it's configuration takes precedent
			setParameters(stageGroup.getStages().iterator().next().getProperties());
		}

		prepared = true;
	}

	private void copyStageLibraryToDirectory(DatabaseFile df, File directory) throws IOException {
		File f = new File(directory, df.getFilename());
		InputStream dfis = df.getInputStream();
		assert(dfis != null);
		FileOutputStream fos = new FileOutputStream(f);
		assert(fos != null);
		try {
			IOUtils.copy(dfis, fos);
		} finally {
			IOUtils.closeQuietly(dfis);
			IOUtils.closeQuietly(fos);
		}
	}

	public final void setParameters(Map<String, Object> conf) {
		if (hasParameter(conf, StageGroup.JAVA_LOCATION_KEY)) {
			java = (String) conf.get(StageGroup.JAVA_LOCATION_KEY);
		}

		if (hasParameter(conf, StageGroup.JVM_PARAMETERS_KEY)) {
			jvmParameters = (String) conf.get(StageGroup.JVM_PARAMETERS_KEY);
		}

		if (hasParameter(conf, StageGroup.RETRIES_KEY)) {
			timesToRetry = (Integer) conf.get(StageGroup.RETRIES_KEY);
		}
	}

	private boolean hasParameter(Map<String, Object> conf, String parameter) {
		return conf.containsKey(parameter) && conf.get(parameter) != null;
	}

	public void run() {
		started = true;
		if (!prepared) {
			logger.error("The StageRunner was not prepared prior to being started. Aborting!");
			return;
		}

		if (stageGroup.isEmpty()) {
			logger.info("Stage group " + stageGroup.getName() + " has no stages, and can not be started.");
			return;
		}

		do {
			logger.info("Starting stage group " + stageGroup.getName()
					+ ". Times started so far: " + timesStarted);
			timesStarted++;
			boolean cleanShutdown = runGroup();
			if (cleanShutdown) {
				return;
			}
			if (!hasQueried()) {
				logger.error("The stage group " + stageGroup.getName() + " did not start. It will not be restarted until configuration changes.");
				return;
			}
		} while ((timesToRetry == -1 || timesToRetry >= timesStarted) && !shutdownHandler.isShuttingDown());

		logger.error("Stage group " + stageGroup.getName()
				+ " has failed and cannot be restarted. ");
	}

	public void printJavaVersion() {
		CommandLine cmdLine = new CommandLine("java");
		cmdLine.addArgument("-version");
		DefaultExecuteResultHandler resultHandler = new DefaultExecuteResultHandler();
		Executor executor = new DefaultExecutor();
		try {
			executor.execute(cmdLine, resultHandler);
		} catch (ExecuteException e) {
			throw new RuntimeException(e);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Launches this stage and waits for process termination.
	 *
	 * @return true if the stage was killed by a call to the destroy()-method. false otherwise.
	 */
	private boolean runGroup() {
		CommandLine cmdLine = new CommandLine(java);
		cmdLine.addArguments(splitJvmParameters(), false);
		cmdLine.addArgument("-cp");
		cmdLine.addArgument("${classpath}", false);
		cmdLine.addArgument(GroupStarter.class.getCanonicalName());
		cmdLine.addArgument(stageGroup.getName());
		cmdLine.addArgument("localhost");
		cmdLine.addArgument("" + pipelinePort);
		cmdLine.addArgument("" + performanceLogging);
		cmdLine.addArgument("" + loggingPort);
		cmdLine.addArgument(startupArgsString);

		HashMap<String, Object> map = new HashMap<String, Object>();

		map.put("classpath", getClassPath());

		cmdLine.setSubstitutionMap(map);
		logger.info("Launching with command " + cmdLine.toString());

		CommandLauncher cl = CommandLauncherFactory.createVMLauncher();

		int exitValue = 0;

		try {
			Process p = cl.exec(cmdLine, null);
			new StreamLogger(
					String.format("%s (stdout)", stageGroup.getName()),
					p.getInputStream()
			).start();
			new StreamLogger(
					String.format("%s (stderr)", stageGroup.getName()),
					p.getErrorStream()
			).start();

			stageDestroyer.add(p);

			exitValue = p.waitFor();

		} catch (InterruptedException e) {
			throw new IllegalStateException("Caught Interrupt while waiting for process exit", e);
		} catch (IOException e) {
			logger.error("Caught IOException while running command", e);
			return false;
		}

		if (!wasKilled) {
			logger.error("Stage group " + stageGroup.getName()
					+ " terminated unexpectedly with exit value " + exitValue);
			return false;
		}
		return true;
	}

	private String[] splitJvmParameters() {
		return jvmParameters.split("(\\s)(?=-)");
	}

	private String getClassPath() {
		String[] jarPaths = targetDirectory.list();
		for (int i = 0; i < jarPaths.length; i++) {
			jarPaths[i] = targetDirectory.getAbsolutePath() + File.separator + jarPaths[i];
		}
		return StringUtils.join(jarPaths, File.pathSeparator);
	}

	/**
	 * Destroys the JVM running this stage and removes it's working files.
	 * Should a JVM shutdown fail, it will throw an IllegalStateException.
	 */
	public void destroy() {
		logger.debug("Attempting to destroy JVM running stage group "
				+ stageGroup.getName());
		boolean success = stageDestroyer.killAll();
		if (success) {
			logger.debug("... destruction successful");
		} else {
			logger.error("JVM running stage group " + stageGroup.getName()
					+ " was not killed");
			throw new IllegalStateException("Orphaned process for "
					+ stageGroup.getName());
		}

		removeFiles();

		wasKilled = true;
	}

	private void removeFiles() {
		long start = System.currentTimeMillis();
		IOException ex = null;
		do {
			try {
				FileUtils.deleteDirectory(targetDirectory);
				return;
			} catch (IOException e) {
				ex = e;
				try {
					Thread.sleep(50);
				} catch (InterruptedException e1) {
					logger.error("Interrupted while waiting on delete");
					Thread.currentThread().interrupt();
					return;
				}
			}
		} while (start + 5000 > System.currentTimeMillis());
		logger.error("Unable to delete the directory "
				+ targetDirectory.getAbsolutePath()
				+ ", containing Stage Group " + stageGroup.getName(), ex);
	}

	public StageGroup getStageGroup() {
		return stageGroup;
	}

	public boolean isStarted() {
		return started;
	}

	/**
	 * Manages the destruction of any Stages launched in this wrapper.
	 * Automatically binds to the Runtime to shut down along with the master
	 * JVM.
	 *
	 * @author joel.westberg
	 */
	static class StageDestroyer extends Thread implements ProcessDestroyer {

		private final List<Process> processes = new ArrayList<Process>();

		public StageDestroyer() {
			Runtime.getRuntime().addShutdownHook(this);
		}

		@Override
		public boolean add(Process p) {
			/**
			 * Register this destroyer to Runtime in order to avoid orphaned
			 * processes if the main JVM dies
			 */
			processes.add(p);
			return true;
		}

		@Override
		public boolean remove(Process p) {
			return processes.remove(p);
		}

		@Override
		public int size() {
			return processes.size();
		}

		/**
		 * Invoked by the runtime ShutdownHook on JVM exit
		 */
		public void run() {
			killAll();
		}

		public boolean killAll() {
			boolean success = true;
			synchronized (processes) {
				for (Process process : processes) {
					try {
						process.destroy();
					} catch (RuntimeException t) {
						success = false;
					}
				}
			}
			return success;
		}
	}

	public void setStageDestroyer(StageDestroyer sd) {
		stageDestroyer = sd;
	}
}
