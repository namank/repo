/*
 * This file is part of the SMT project.
 * Copyright 2010 David R. Cok
 * Created August 2010
 * 
 * Modifications by Namank Shah and Seule Ki Kim
 * Boston University
 * For CS 512: Formal Methods
 * Spring 2013
 */
package org.smtlib;

import java.io.BufferedReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/** This class implements launching, writing to, and reading responses from a 
 * launched process (in particular, solver processes).
 * @author David Cok
 */
public class SolverProcess {
	
	final static protected String eol = System.getProperty("line.separator");
	
	/** Wraps an exception thrown because of a failure in the prover */
	public static class ProverException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		public ProverException(String s) { super(s); }
	}
	
	/** The command-line arguments that launch a new process */
	protected String[] app;

	/** The text that marks the end of the text returned from the process */
	protected String endMarker;

	/** The Java process object (initialized by start() )*/
	protected Process process;
	
	/** The Writer object that writes to the spawned process (initialized by start() )*/
	protected Writer toProcess;
	
	/** The Reader process that reads from the standard output of the spawned process (initialized by start() )*/
	protected Reader fromProcess;
	
	/** The Reader process that reads from the standard error stream of the spawned process (initialized by start() )*/
	protected Reader errors;
	
	/** A place (e.g., log file), if non-null, to write all outbound communications for diagnostic purposes */
	public /*@Nullable*/Writer log;
	
	/** Constructs a SolverProcess object, without actually starting the process as yet.
	 * @param cmd the command-line that will launch the desired process
	 * @param endMarker text that marks the end of text returned from the process, e.g. the end of the 
	 * prompt for new input
	 * @param logfile if not null, the name of a file to log communications to, for diagnostic purposes
	 */
	public SolverProcess(String[] cmd, String endMarker, /*@Nullable*/String logfile) {
		setCmd(cmd);
		this.endMarker = endMarker;
		try {
			if (logfile != null) log = new FileWriter(logfile);
		} catch (IOException e) {
			System.out.println("Failed to create solver log file " + logfile + ": " + e); // FIXME - wwrite to somewhere better
		}
	}
	
	/** Enables changing the command-line; must be called prior to start() */
	public void setCmd(String[] cmd) {
		this.app = cmd;
	}
	
	/** Starts the process; if the argument is true, then also listens to its output until a prompt is read. */
    public void start(boolean listen) throws ProverException {
    	try {
    		process = Runtime.getRuntime().exec(app);
    		toProcess = new OutputStreamWriter(process.getOutputStream());
    		fromProcess = new BufferedReader(new InputStreamReader(process.getInputStream()));
    		errors = new InputStreamReader(process.getErrorStream());
    		if (listen) listen();
    	} catch (IOException e) {
    		throw new ProverException(e.getMessage());
    	} catch (RuntimeException e) {
    		throw new ProverException(e.getMessage());
    	}
    }

    /** Listens to the process's standard output until the designated endMarker is read 
     * and to the error output. If there is error output, it is returned;
     * otherwise the standard output is returned.
     */
	public String listen() throws IOException {
		// FIXME - need to put the two reads in parallel, otherwise one might block on a full buffer, preventing the other from completing
		String err = listenThru(errors,null);
		String out = listenThru(fromProcess,endMarker);
		err = err + listenThru(errors,null);
		
		if (log != null) {
			if (!out.isEmpty()) { log.write("OUT: "); log.write(out); log.write(eol); } // input usually ends with a prompt and no line terminator
			if (!err.isEmpty()) { log.write("ERR: "); log.write(err); } // input usually ends with a line terminator, we think
		}
		return err.isEmpty() ? out : err;
	}
	
	/** Aborts the process */
	public void exit() {
		process.destroy();
		process = null;
		toProcess = null;
		if (log != null) {
			try {
				log.write("Exiting solver"); 
				log.write(eol);
				log.flush();
				log.close();
			} catch (IOException e) {
				// Ignore
			}
		}
	}
	
	/** Sends all the given text arguments, then (if listen is true) listens for the designated end marker text */
	public /*@Nullable*/ String send(boolean listen, String ... args) throws IOException {
		if (toProcess == null) throw new ProverException("The solver has not been started");
		for (String arg: args) {
			if (log != null) log.write(arg);
			toProcess.write(arg);
		}
		if (log != null) log.flush();
		toProcess.flush();
		if (listen) return listen();
		return null;
	}

	/** Sends all the given text arguments, then listens for the designated end marker text */
	public /*@Nullable*/ String sendAndListen(String ... args) throws IOException {
		return send(true,args);
	}

	/** Sends all the given text arguments, but does not wait for a response */
	public void sendNoListen(String ... args) throws IOException {
		send(false,args);
	}

// TODO - combine listen and noListen versions of send?
	
	/** A pool of buffers used by listenThru. The listenThru method needs a buffer, which may need to be big.
	 *  However, the method is called often and we do not want to be continually allocating big buffers that
	 *  have to wait around to be garbage collected.  Especially since, unless there are multiple SMT processes
	 *  working simultaneously, we will never need more than one of these.  But in order to be thread-safe we 
	 *  cannot simply declare a static buffer.
	 */
	static private List<char[]> bufferCollection = Collections.synchronizedList(new LinkedList<char[]>());
	
	/** Gets a buffer out of the shared free-list of buffers 
	 * @return a free buffer available to be used
	 */
	synchronized private static char[] getBuffer() {
		char[] buf;
		if (bufferCollection.isEmpty()) {
			// There is nothing magic about the size of the buffers - just meant to be generally enough to
			// hold the output of a read, but not so large as to unnecessarily use memory. 
			// If it is not large enough, it will be expanded.
			buf = new char[10000];
		} else {
			buf = bufferCollection.remove(0);
		}
		return buf;
	}
	
	/** Puts a buffer back into the shared free-list.
	 * @param buf the buffer being released
	 */
	synchronized private static void putBuffer(char[] buf) {
		bufferCollection.add(buf);
	}
	
	/** Reads the given Reader until the given String is read (or end of input is reached);
	 * may block until input is available; the stopping string must occur at the end of the
	 * input.  This is typically used to read through a prompt on standard output; when the stopping
	 * string (the prompt) is read, one knows that the output from the program is complete and not
	 * to wait for any more.
	 * 
	 * @param r the Reader to read characters from
	 * @param end a stopping String
	 * @return the String read
	 * @throws IOException if an IO failure occurs
	 */
	static public /*@NonNull*/String listenThru(/*@NonNull*/Reader r, /*@Nullable*/ String end) throws IOException {
		char[] buf = getBuffer();
		try {
			int len = end != null ? end.length() : 0;
			int p = 0; // Number of characters read
			while (end != null || r.ready()) {
				//System.out.println("ABOUT TO READ " + p);
				int i = r.read(buf,p,buf.length-p);
				if (i == -1) break; // End of Input
				p += i;
				//System.out.println("HEARD: " + new String(buf,0,p));
				if (end != null && p >= len) {
					// Need to compare a String to a part of a char array - we iterate by
					// hand to avoid creating a new String or CharBuffer object
					boolean match = true;
					int k = p-len;
					for (int j=0; j<len; j++) {
						if (end.charAt(j) != buf[k++]) { match = false; break; }
					}
					if (match) break; // stopping string matched
				}
				if (p == buf.length) { // expand the buffer
					char[] nbuf = new char[2*buf.length];
					System.arraycopy(buf,0,nbuf,0,p);
					buf = nbuf;
				}
			}
			return new String(buf,0,p);
		} finally {
			putBuffer(buf);
		}
	}
}
