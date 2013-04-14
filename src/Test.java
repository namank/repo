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
		config.solverVerbosity=5;
		config.noshow=true;
		config.relax=true;
		Solver_z3_4_3 a = new Solver_z3_4_3(config, "C:\\Users\\Namank\\Documents\\College\\Spring 13\\CS 512\\jSMTLib\\z3-4.3.0-x86\\z3-4.3.0-x86\\bin\\z3.exe");
		
		if (a.start().isError())
			throw new Exception("could not start");
		
		Factory f = new Factory();
		Parser p = f.createParser(config, f.createSource(config, new File ("C:\\Users\\Namank\\Documents\\workspace\\SMTTest\\src\\test.smt2")));
		while (!p.isEOD())
		{
			Command cmd = p.parseCommand();
			System.out.println(cmd);
			/*
			//first token is an LP
			//check what 2nd token is
			//if 2nd token is declare-const, then add our own functions, otherwise create an LP token and then do parseCommand and execute
			ILexToken token = p.peekToken();
			IPos lpPos = token.pos();
			int cPos = p.currentPos();
			if (token.toString().equals("("))
				p.getToken();
			token = p.peekToken();
			
			if (!(token.toString().equals("declare-const")))
			{
				p.LP(cPos);
				
				System.out.println(p.currentPos());
				Command cmd = p.parseCommand();
				cmd.execute(a);
			}
			else
			{	
				System.out.println(p.getToken());
			}
			//System.out.println(p.getToken());
			/*ISexpr.ISeq expr = (ISexpr.ISeq) (p.parseSexpr());
			LinkedList<ISexpr> list = (LinkedList<ISexpr>) expr.sexprs();
			System.out.println(list.getFirst().kind());
			if (list.getFirst().toString().equals("declare-const"))
			{
				System.out.println(list.getLast().toString());
			}
			//System.out.println(cmd.commandName());
			/*
			if (cmd.kind().equals("fcn"))
			{
				SMTExpr.FcnExpr cmdName = (SMTExpr.FcnExpr) cmd;				
				switch (cmdName.head().toString())
				{
					case "echo":
						System.out.println(cmdName.args().get(0));
					
					
				}
					
			}*/
			
			//System.out.println(cmd.execute(a));
		}
		/*if (a.start().isOK())
		{
			a.set_logic("QF_UF", null);
		}
		
		a.declare_fun(new C_declare_fun(new SMTExpr.Symbol("x"), null, (new Sort.FcnSort(null)).Bool()));
		System.out.println(a.check_sat());
		a.exit();*/
		}
		catch (Exception e)
		{
			System.out.println(e.getMessage());
		}	
	}
}
