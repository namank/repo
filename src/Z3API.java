/* Created by Namank Shah and Seule Ki Kim
 * Boston University
 * For CS 512: Formal Methods
 * Spring 2013
 */

import org.smtlib.*;
import org.smtlib.solvers.*;
import org.smtlib.impl.*;
import org.smtlib.sexpr.*;
import org.smtlib.sexpr.Factory;
import java.io.File;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionException;

public class Z3API
{
	public static void main(String [] args)
	{
		//configure the command line option parser
		OptionParser op = new OptionParser();
		op.accepts("file", "The SMT2 inpput file").withRequiredArg();
		op.accepts("exec", "The executable file for Z3 solver").withRequiredArg();
		op.accepts("verbose", "Configure verbosity of the solver").withRequiredArg();
		op.accepts("help", "Print help features").forHelp();
		try
		{			
			//parse the options and give error message if missing things
			OptionSet results = op.parse(args);
			if (results.has("help"))
			{
				System.out.println("Usage: java Z3API [-h] [-exec <path to executable>] [-file <path to input file] [-verbose <verbosity level of solver>]");
				return;
			}
			if (!(results.has("exec") && results.hasArgument("exec")))
			{
				System.out.println("The executable is required. Use the flag -h for help/usage instructions.");
				return;
			}
			if (!(results.has("file") && results.hasArgument("file")))
			{
				System.out.println("The input file is required. Use the flag -h for help/usage instructions.");
				return;
			}
			
			//set up the configuration of the Z3 solver
			SMT.Configuration config = new SMT.Configuration();
			config.verbose = results.has("verbose") ? Integer.parseInt((String) results.valueOf("verbose")) : 0;
			config.noshow=true;
			//since we have added some commands for convenience, we set relax to true to prevent strict adherence to input format
			config.relax=true;
			Solver_z3_4_3 solver = new Solver_z3_4_3(config, (String) results.valueOf("exec"));
			
			//exit if invalid executable path given by user
			if (solver.start().isError())
				throw new Exception("Could not start Z3");
			
			//create the parser
			Factory fact = new Factory();
			Parser parser = fact.createParser(config, fact.createSource(config, new File ((String) results.valueOf("file"))));
			while (!parser.isEOD())
			{
				//parse a command, since it is an SMT2 input file
				Command cmd = parser.parseCommand();
				//print the command and then execute it
				if (cmd != null) 
					System.out.println(cmd + "\n"+cmd.execute(solver));
				else
					throw new Exception("A command is not valid. Please check the syntax and try again.");
			}		
		}
		//catch any errors thrown by command line parser
		catch(OptionException e ) {
		    System.err.println(e.getMessage());		    
		    return;
		}
		//catch exception thrown by invalid file or executable
		catch (Exception e)
		{
			System.out.println(e.toString());
		}
	}
}