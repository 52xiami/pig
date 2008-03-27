/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.pig.impl.streaming;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Properties;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.pig.backend.executionengine.ExecException;
import org.apache.pig.data.Datum;
import org.apache.pig.data.Tuple;
import org.apache.pig.impl.eval.collector.DataCollector;
import org.apache.pig.impl.streaming.InputHandler.InputType;
import org.apache.pig.impl.streaming.OutputHandler.OutputType;

/**
 * {@link ExecutableManager} manages an external executable which processes data
 * in a Pig query.
 * 
 * The <code>ExecutableManager</code> is responsible for startup/teardown of the 
 * external process and also for managing it.
 * It feeds input records to the executable via it's <code>stdin</code>, 
 * collects the output records from the <code>stdout</code> and also diagnostic 
 * information from the <code>stdout</code>.
 */
public class ExecutableManager {
	private static final Log LOG = 
		LogFactory.getLog(ExecutableManager.class.getName());
    private static final int SUCCESS = 0;

	protected StreamingCommand command;        // Streaming command to be run
	String[] argv;                             // Parsed/split commands

	Process process;                           // Handle to the process
    protected int exitCode = -127;             // Exit code of the process
	
	protected DataOutputStream stdin;          // stdin of the process
	
	ProcessOutputThread stdoutThread;          // thread to get process output
	InputStream stdout;                        // stdout of the process
	                                           // interpret the process' output
	
	ProcessErrorThread stderrThread;           // thread to get process output
	InputStream stderr;                        // stderr of the process
	
	DataCollector endOfPipe;

	// Input/Output handlers
	InputHandler inputHandler;
	OutputHandler outputHandler;

	Properties properties;

	protected long inputBytes = 0;
	protected long outputBytes = 0;
	
	public ExecutableManager() {}
	
	public void configure(Properties properties, StreamingCommand command, 
	                      DataCollector endOfPipe) 
	throws IOException, ExecException {
	    this.properties = properties;
	    
		this.command = command;
		this.argv = this.command.getCommandArgs();

		// Create the input/output handlers
		this.inputHandler = HandlerFactory.createInputHandler(command);
		this.outputHandler = 
		    HandlerFactory.createOutputHandler(command);
		
		// Successor
		this.endOfPipe = endOfPipe;
	}
	
	public void close() throws IOException, ExecException {
	    // Close the InputHandler, which in some cases lets the process
	    // terminate
		inputHandler.close();
		
		// Check if we need to start the process now ...
		if (inputHandler.getInputType() == InputType.ASYNCHRONOUS) {
		    exec();
		}
		
		// Wait for the process to exit and the stdout/stderr threads to complete
		try {
			exitCode = process.waitFor();
			
			if (stdoutThread != null) {
			    stdoutThread.join(0);
			}
			if (stderrThread != null) {
				stderrThread.join(0);
			}

		} catch (InterruptedException ie) {}

		// Clean up the process
		process.destroy();
		
        LOG.debug("Process exited with: " + exitCode);
        if (exitCode != SUCCESS) {
            throw new ExecException(command + " failed with exit status: " + 
                                       exitCode);
        }
        
        if (outputHandler.getOutputType() == OutputType.ASYNCHRONOUS) {
            // Trigger the outputHandler
            outputHandler.bindTo(null);

            // Start the thread to process the output and wait for
            // it to terminate
            stdoutThread = new ProcessOutputThread(outputHandler);
            stdoutThread.start();
            
            try {
                stdoutThread.join(0);
            } catch (InterruptedException ie) {}
        }

	}

	protected void exec() throws IOException {
	    // Unquote command-line arguments ...
	    for (int i=0; i < argv.length; ++i) {
	        String arg = argv[i];
	        if (arg.charAt(0) == '\'' && arg.charAt(arg.length()-1) == '\'') {
	            argv[i] = arg.substring(1, arg.length()-1);
	        }
	    }
	    
        // Start the external process
        ProcessBuilder processBuilder = new ProcessBuilder(argv);
        process = processBuilder.start();
        LOG.debug("Started the process for command: " + command);
        
        // Pick up the process' stderr stream and start the thread to 
        // process the stderr stream
        stderr = 
            new DataInputStream(new BufferedInputStream(process.getErrorStream()));
        stderrThread = new ProcessErrorThread();
        stderrThread.start();

        // Check if we need to handle the process' stdout directly
        if (outputHandler.getOutputType() == OutputType.SYNCHRONOUS) {
            // Get hold of the stdout of the process
            stdout = 
                new DataInputStream(new BufferedInputStream(process.getInputStream()));
            
            // Bind the stdout to the OutputHandler
            outputHandler.bindTo(stdout);
            
            // Start the thread to process the executable's stdout
            stdoutThread = new ProcessOutputThread(outputHandler);
            stdoutThread.start();
        }
	}
	
	public void run() throws IOException {
	    // Check if we need to exec the process NOW ...
	    if (inputHandler.getInputType() == InputType.ASYNCHRONOUS) {
	        return;
	    }
	    
		// Start the executable ...
	    exec();
        stdin = 
            new DataOutputStream(new BufferedOutputStream(process.getOutputStream()));
	    inputHandler.bindTo(stdin);
	}

	public void add(Datum d) throws IOException {
		// Pass the serialized tuple to the executable via the InputHandler
	    Tuple t = (Tuple)d;
	    inputHandler.putNext(t);
	    inputBytes += t.getMemorySize();
	}

	/**
	 * Workhorse to process the output of the managed process.
	 * 
	 * The <code>ExecutableManager</code>, by default, just pushes the received
	 * <code>Datum</code> into eval-pipeline to be processed by the successor.
	 * 
	 * @param d <code>Datum</code> to process
	 */
	protected void processOutput(Datum d) {
		endOfPipe.add(d);
	}
	
	class ProcessOutputThread extends Thread {

	    OutputHandler outputHandler;

		ProcessOutputThread(OutputHandler outputHandler) {
			setDaemon(true);
			this.outputHandler = outputHandler;
		}

		public void run() {
			try {
				// Read tuples from the executable and push them down the pipe
				Tuple tuple = null;
				while ((tuple = outputHandler.getNext()) != null) {
					processOutput(tuple);
					outputBytes += tuple.getMemorySize();
				}

				outputHandler.close();
			} catch (Throwable t) {
				LOG.warn(t);
				try {
				    outputHandler.close();
				} catch (IOException ioe) {
					LOG.info(ioe);
				}
				throw new RuntimeException(t);
			}
		}
	}

	/**
	 * Workhorse to process the stderr stream of the managed process.
	 * 
	 * By default <code>ExecuatbleManager</code> just sends out the received
	 * error message to the <code>stderr</code> of itself.
	 * 
	 * @param error error message from the managed process.
	 */
	protected void processError(String error) {
		// Just send it out to our stderr
		System.err.print(error);
	}
	
	class ProcessErrorThread extends Thread {

		public ProcessErrorThread() {
			setDaemon(true);
		}

		public void run() {
			try {
				String error;
				BufferedReader reader = 
					new BufferedReader(new InputStreamReader(stderr));
				while ((error = reader.readLine()) != null) {
					processError(error+"\n");
				}

				if (stderr != null) {
					stderr.close();
					LOG.debug("ProcessErrorThread done");
				}
			} catch (Throwable th) {
				LOG.warn(th);
				try {
					if (stderr != null) {
						stderr.close();
					}
				} catch (IOException ioe) {
					LOG.info(ioe);
	                throw new RuntimeException(th);
				}
			}
		}
	}
}
