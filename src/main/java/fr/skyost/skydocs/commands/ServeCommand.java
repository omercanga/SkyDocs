package fr.skyost.skydocs.commands;

import java.awt.Desktop;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.util.Scanner;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.monitor.FileAlterationListenerAdaptor;
import org.apache.commons.io.monitor.FileAlterationMonitor;
import org.apache.commons.io.monitor.FileAlterationObserver;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response.Status;
import fr.skyost.skydocs.Constants;
import fr.skyost.skydocs.DocsProject;
import fr.skyost.skydocs.exceptions.LoadException;
import fr.skyost.skydocs.utils.Utils;
import fr.skyost.skydocs.utils.Utils.AutoLineBreakStringBuilder;

/**
 * "serve" command.
 */

public class ServeCommand extends Command {
	
	/**
	 * The project's build directory.
	 */
	
	private File buildDirectory;
	
	/**
	 * The server's port.
	 */
	
	private final int port;
	
	/**
	 * Last build time in millis.
	 */
	
	private long lastBuild;
	
	public ServeCommand(final String... args) {
		super(args);
		this.port = args.length >= 2 && Utils.parseInt(args[1]) != null ? Utils.parseInt(args[1]) : Constants.DEFAULT_PORT;
	}
	
	@Override
	public final void run() {
		try {
			final InternalServer server = new InternalServer(port);
			
			final BuildCommand command = new BuildCommand(false, this.getArguments());
			command.setOutputing(false);
			boolean firstBuild = true;
			blankLine();
			
			final Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8.name());
			String line = "";
			while(line.equals("")) {
				newBuild(command, firstBuild);
				if(firstBuild) {
					registerFileListener(command);
					buildDirectory = command.getCurrentBuildDirectory();
					outputLine("You can point your browser to http://localhost:" + port + ".");
					server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
					if(Desktop.isDesktopSupported()) {
						Desktop.getDesktop().browse(new URL("http://localhost:" + port).toURI());
					}
				}
				firstBuild = false;
				outputLine("Enter nothing to rebuild the website or enter something to stop the server (auto rebuild is enabled) :");
				blankLine();
				line = scanner.hasNextLine() ? scanner.nextLine() : " ";
			}
			scanner.close();
			server.stop();
		}
		catch(final Exception ex) {
			printStackTrace(ex);
		}
		super.run();
	}
	
	@Override
	public final boolean isInterruptible() {
		return false;
	}
	
	/**
	 * Triggers a new build.
	 * 
	 * @param command The build command.
	 * @param firstBuild If this is the first build (if not, the command will reload the project).
	 * 
	 * @throws LoadException If an error occurs while loading the project.
	 */
	
	private final void newBuild(final BuildCommand command, final boolean firstBuild) throws LoadException {
		output("Running build command...");
		firstTime();
		
		if(!command.isInterrupted()) {
			command.interrupt();
		}
		if(!firstBuild) {
			command.reloadProject();
		}
		command.run();
		lastBuild = System.currentTimeMillis();

		secondTime();
		printTimeElapsed();
	}
	
	/**
	 * Creates and registers a file listener with the help of the specified build command.
	 * 
	 * @param command The build command.
	 * 
	 * @throws Exception If any exception occurs.
	 */
	
	private final void registerFileListener(final BuildCommand command) throws Exception {
		final DocsProject project = command.getProject();
		
		final FileAlterationObserver observer = new FileAlterationObserver(project.getDirectory());
		final FileAlterationMonitor monitor = new FileAlterationMonitor(Constants.SERVE_FILE_POLLING_INTERVAL);
		observer.addListener(new FileAlterationListenerAdaptor() {
			
			@Override
			public final void onDirectoryChange(final File directory) {
				rebuildIfNeeded(command, directory);
			}
			
			@Override
			public final void onFileChange(final File file) {
				rebuildIfNeeded(command, file);
			}
			
		});
		monitor.addObserver(observer);
		monitor.start();
	}
	
	/**
	 * Rebuilds the project if needed.
	 * 
	 * @param command The build command (we need it to build the project).
	 * @param file The file that has changed.
	 */
	
	private final void rebuildIfNeeded(final BuildCommand command, final File file) {
		final String path = file.getPath().replace(command.getProject().getDirectory().getPath(), "").substring(1);
		boolean reBuild = false;
		for(final String toRebuild : Constants.SERVE_REBUILD_PREFIX) {
			if(path.startsWith(toRebuild)) {
				reBuild = true;
			}
		}
		if(!reBuild) {
			return;
		}
		try {
			newBuild(command, false);
			outputLine("Enter nothing to rebuild the website or enter something to stop the server (auto rebuild is enabled) :");
			blankLine();
		}
		catch(final Exception ex) {
			printStackTrace(ex);
			outputLine("Unable to build the project !");
		}
	}
	
	/**
	 * Gets the current port of this serve command.
	 * 
	 * @return The current port of this serve command.
	 */
	
	public final int getPort() {
		return port;
	}
	
	/**
	 * The SkyDocs internal server.
	 */
	
	private class InternalServer extends NanoHTTPD {
		
		public InternalServer(final int port) {
			super(port);
		}
		
		@Override
		public final Response serve(final IHTTPSession session) {
			try {
				final String currentUri = session.getUri();
				if(currentUri.equals("/" + Constants.SERVE_LASTBUILD_URL) || currentUri.equals("/" + Constants.SERVE_LASTBUILD_URL + "/")) {
					return newFixedLengthResponse(Status.OK, NanoHTTPD.MIME_PLAINTEXT, String.valueOf(lastBuild));
				}
				File file = new File(buildDirectory.getPath() + currentUri.replace("/", File.separator));
				if(file.isDirectory()) {
					if(!currentUri.endsWith("/")) {
						final Response response = newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML, "Redirecting you...");
						response.addHeader("Location", currentUri + "/");
						return response;
					}
					file = new File(file, "index.html");
					if(!file.exists()) {
						final AutoLineBreakStringBuilder builder = new AutoLineBreakStringBuilder("<!DOCTYPE html>");
						builder.append("<html>");
						builder.append("	<head>");
						builder.append("		<title>" + currentUri + "</title>");
						builder.append("	</head>");
						builder.append("	<body>");
						builder.append("		<h1>" + currentUri + "</h1>");
						builder.append("		<hr/>");
						builder.append("		<ul>");
						builder.append("			<li><a href=\"../\">..</a></li>");
						for(final File child : file.getParentFile().listFiles()) {
							String path = child.getPath().replace(buildDirectory.getPath(), "").replace(File.separator, "/");
							if(child.isDirectory()) {
								path += "/";
							}
							builder.append("			<li><a href=\"" + path + "\">" + child.getName() + "</a></li>");
						}
						builder.append("		</ul>");
						builder.append("		<hr/>");
						builder.append("		<p style=\"text-align:right;\"><a href=\"" + Constants.APP_WEBSITE + "\">" + Constants.APP_NAME + " " + Constants.APP_VERSION + "</a></p>");
						builder.append(Utils.AUTO_REFRESH_SCRIPT);
						builder.append("	</body>");
						builder.append("</html>");
						return newFixedLengthResponse(Response.Status.UNAUTHORIZED, NanoHTTPD.MIME_HTML, builder.toString());
					}
				}
				if(FilenameUtils.getExtension(file.getName()).equalsIgnoreCase("html")) {
					String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
					if(content.contains("<body>") && content.contains("</body>")) {
						content = content.replace("</body>", Utils.AUTO_REFRESH_SCRIPT + "</body>");
						return newFixedLengthResponse(Status.OK, NanoHTTPD.MIME_HTML, content);
					}
				}
				final FileInputStream fileInput = new FileInputStream(file);
				final BufferedInputStream bufferedInput = new BufferedInputStream(fileInput);
				return newFixedLengthResponse(Status.OK, NanoHTTPD.getMimeTypeForFile(file.getPath()), bufferedInput, -1);
			}
			catch(final NoSuchFileException ex) {
				return newFixedLengthResponse(Status.NOT_FOUND, NanoHTTPD.MIME_HTML, "<html><head><title>404 Error</title></head><body>404 File not found." + Utils.AUTO_REFRESH_SCRIPT + "</body></html>");
			}
			catch(final FileNotFoundException ex) {
				return newFixedLengthResponse(Status.NOT_FOUND, NanoHTTPD.MIME_HTML, "<html><head><title>404 Error</title></head><body>404 File not found (or inaccessible)." + Utils.AUTO_REFRESH_SCRIPT + "</body></html>");
			}
			catch(final Exception ex) {
				return newFixedLengthResponse(Status.NOT_FOUND, NanoHTTPD.MIME_HTML, "<html><head><title>Error</title></head><body>" + ex.getClass().getName() + Utils.AUTO_REFRESH_SCRIPT + "</body></html>");
			}
		}
		
	}
	
}