/* Modifications by Namank Shah and Seule Ki Kim
 * Boston University
 * For CS 512: Formal Methods
 * Spring 2013
 */
import org.smtlib.*;
import org.smtlib.solvers.*;
import org.smtlib.impl.*;
import org.smtlib.command.*;
import org.smtlib.sexpr.*;
import org.smtlib.sexpr.Sexpr;
import org.smtlib.sexpr.Factory;
import org.smtlib.ext.*;
import java.io.File;

import java.util.LinkedList;

public class Test {
	
	public static void main(String [] args)
	{
		try
		{
			SMT.Configuration config = new SMT.Configuration();
			config.verbose = 0;
			//config.solverVerbosity=5;
			config.noshow=true;
			config.relax=true;
			Solver_z3_4_3 a = new Solver_z3_4_3(config, "C:\\Users\\Namank\\Documents\\College\\Spring 13\\CS 512\\jSMTLib\\z3-4.3.0-x86\\z3-4.3.0-x86\\bin\\z3.exe");
			//Solver_yices y = new Solver_yices(config, "C:\\Users\\Namank\\Documents\\College\\Spring 13\\CS 512\\Yices\\yices-2.1.0\\bin\\yices.exe");
			if (a.start().isError())
				throw new Exception("could not start");
			/*if (y.start().isError())
				throw new Exception("could not start Yices");*/
			Factory f = new Factory();
			Parser p = f.createParser(config, f.createSource(config, new File ("C:\\Users\\Namank\\Documents\\workspace\\SMTTest\\src\\test.smt2")));
			while (!p.isEOD())
			{
				Command cmd = p.parseCommand();
				if (cmd != null) 
					System.out.println(cmd + "\n"+cmd.execute(a));
				//System.out.println(cmd.execute(y));
			}		
		}
		catch (Exception e)
		{
			System.out.println(e.getMessage());
		}	
	}
}