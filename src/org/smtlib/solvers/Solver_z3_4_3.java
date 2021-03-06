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
package org.smtlib.solvers;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringWriter;
import java.io.Writer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.smtlib.*;
import org.smtlib.ICommand.Ideclare_fun;
import org.smtlib.ICommand.Ideclare_sort;
import org.smtlib.ICommand.Idefine_fun;
import org.smtlib.ICommand.Idefine_sort;
import org.smtlib.IExpr.IAttribute;
import org.smtlib.IExpr.IAttributeValue;
import org.smtlib.IExpr.IFcnExpr;
import org.smtlib.IExpr.IIdentifier;
import org.smtlib.IExpr.IKeyword;
import org.smtlib.IExpr.INumeral;
import org.smtlib.IExpr.IQualifiedIdentifier;
import org.smtlib.IExpr.IStringLiteral;
import org.smtlib.IParser.ParserException;
import org.smtlib.impl.Pos;
import org.smtlib.sexpr.Printer;

/** This class is an adapter that takes the SMT-LIB ASTs and translates them into Z3 commands */
public class Solver_z3_4_3 extends AbstractSolver implements ISolver {
	
	protected String NAME_VALUE = "z3-4.3";
	protected String AUTHORS_VALUE = "Leonardo de Moura and Nikolaj Bjorner";
	protected String VERSION_VALUE = "4.3";
	
	/** A reference to the SMT configuration */
	protected SMT.Configuration smtConfig;

	/** A reference to the SMT configuration */
	public SMT.Configuration smt() { return smtConfig; }
	
	/** The command-line arguments for launching the Z3 solver */
	protected String cmds[] = new String[]{ "", "/smt2","/in","SMTLIB2_COMPLIANT=true"}; 

	/** The object that interacts with external processes */
	protected SolverProcess solverProcess;
	
	/** The parser that parses responses from the solver */
	protected org.smtlib.sexpr.Parser responseParser;
	
	/** Set to true once a set-logic command has been executed */
	private boolean logicSet = false;
	
	/** The checkSatStatus returned by check-sat, if sufficiently recent, otherwise null */
	protected /*@Nullable*/ IResponse checkSatStatus = null;
	
	@Override
	public /*@Nullable*/IResponse checkSatStatus() { return checkSatStatus; }

	/** The number of assertions on the top assertion stack */
	private int pushes = 0; // FIXME - not needed
	
	/** A stack storing the numbers of assertions in previous assertion sets */
	private List<Integer> pushesStack = new LinkedList<Integer>();
	{
		pushesStack.add(0);
	}
	
	/** Map that keeps current values of options */
	protected Map<String,IAttributeValue> options = new HashMap<String,IAttributeValue>();
	{ 
		options.putAll(Utils.defaults);
	}
	
	/** Creates an instance of the Z3 solver */
	public Solver_z3_4_3(SMT.Configuration smtConfig, /*@NonNull*/ String executable) {
		this.smtConfig = smtConfig;
		cmds[0] = executable;
		solverProcess = new SolverProcess(cmds,"\n","solver.out.z3");
		responseParser = new org.smtlib.sexpr.Parser(smt(),new Pos.Source("",null));
	}
	
	@Override
	public IResponse start() {
		try {
			solverProcess.start(false);
			if (smtConfig.solverVerbosity > 0) solverProcess.sendNoListen("(set-option :verbosity ",Integer.toString(smtConfig.solverVerbosity),")");
			// Can't turn off printing success, or we get no feedback
			solverProcess.sendAndListen("(set-option :print-success true)\n"); // Z3 4.3.0 needs this because it mistakenly has the default for :print-success as false
			if (smtConfig.verbose != 0) smtConfig.log.logDiag("Started Z3-4.3 ");
			return smtConfig.responseFactory.success();
		} catch (Exception e) {
			return smtConfig.responseFactory.error("Failed to start process " + cmds[0] + " : " + e.getMessage());
		}
	}
	
	@Override
	public IResponse exit() {
		try {
			solverProcess.sendAndListen("(exit)\n");
			solverProcess.exit();
			if (smtConfig.verbose != 0) smtConfig.log.logDiag("Ended Z3 ");
			return smtConfig.responseFactory.success_exit();
		} catch (IOException e) {
			return smtConfig.responseFactory.error("Error writing to Z3 solver: " + e);
		}
	}
	//reset declarations and assertions made so far
	@Override
	public IResponse reset() {
		try {
			String s = solverProcess.sendAndListen("(reset)\n");
			IResponse response = parseResponse(s);
			//need to reset the state so far on our side as well
			logicSet=false;
			checkSatStatus = null;
			pushes = 0;
			pushesStack = new LinkedList<Integer>();
			pushesStack.add(0);
			options = new HashMap<String,IAttributeValue>();
			options.putAll(Utils.defaults);
			return response;
		} catch (IOException e) {
			return smtConfig.responseFactory.error("Error writing to Z3 solver: " + e);
		}
	}
	
	/** Translates an S-expression into Z3 syntax */
	protected String translate(IAccept sexpr) throws IVisitor.VisitorException {
		// The z3 solver uses the standard S-expression concrete syntax, but not quite
		// so we have to use our own translator
		StringWriter sw = new StringWriter();
		sexpr.accept(new Translator(sw));
		return sw.toString();
	}
	
	/** Translates an S-expression into standard SMT syntax */
	protected String translateSMT(IAccept sexpr) throws IVisitor.VisitorException {
		// The z3 solver uses the standard S-expression concrete syntax, but not quite
		StringWriter sw = new StringWriter();
		org.smtlib.sexpr.Printer.write(sw,sexpr);
		return sw.toString();
	}
	
	protected IResponse parseResponse(String response) {
		try {
			Pattern oldbv = Pattern.compile("bv([0-9]+)\\[([0-9]+)\\]");
			Matcher mm = oldbv.matcher(response);
			while (mm.find()) {
				long val = Long.parseLong(mm.group(1));
				int base = Integer.parseInt(mm.group(2));
				String bits = "";
				for (int i=0; i<base; i++) { bits = ((val&1)==0 ? "0" : "1") + bits; val = val >>> 1; }
				response = response.substring(0,mm.start()) + "#b" + bits + response.substring(mm.end(),response.length());
				mm = oldbv.matcher(response);
			}
			if (response.contains("error")) {
				Pattern p = Pattern.compile("\\p{Space}*\\(\\p{Blank}*error\\p{Blank}+\"(([\\p{Print}\\p{Space}&&[^\"\\\\]]|\\\\\")*)\"\\p{Blank}*\\)");
				Matcher m = p.matcher(response);
				String concat = "";
				while (m.lookingAt()) {
					if (!concat.isEmpty()) concat = concat + "; ";
					String matched = m.group(1);
					concat = concat + matched;
					m.region(m.end(0),m.regionEnd());
				}
				if (!concat.isEmpty()) response = concat;
				return smtConfig.responseFactory.error(response);
			}
			responseParser = new org.smtlib.sexpr.Parser(smt(),new Pos.Source(response,null));
			return responseParser.parseResponse(response);
		} catch (ParserException e) {
			return smtConfig.responseFactory.error("ParserException while parsing response: " + response + " " + e);
		}
	}

	@Override
	public IResponse assertExpr(IExpr sexpr) {
		IResponse response;
		if (pushesStack.size() == 0) {
			return smtConfig.responseFactory.error("All assertion sets have been popped from the stack");
		}
		if (!logicSet) {
			return smtConfig.responseFactory.error("The logic must be set before an assert command is issued");
		}
		try {			
			String s = solverProcess.sendAndListen("(assert ",translate(sexpr),")\n");
			response = parseResponse(s);
			pushes++; // FIXME
			checkSatStatus = null;
		} catch (IVisitor.VisitorException e) {
			return smtConfig.responseFactory.error("Failed to assert expression: " + e + " " + sexpr);
		} catch (Exception e) {
			return smtConfig.responseFactory.error("Failed to assert expression: " + e + " " + sexpr);
		}
		return response;
	}
	
	//evaluate the given expression and return response from Z3
	//we do not bother parsing the response, because the output sort may vary based on the result sort of function expression
	public IResponse evalExpr(IExpr sexpr) {
		
		if (!logicSet) {
			return smtConfig.responseFactory.error("The logic must be set before an eval command is issued");
		}
		if (checkSatStatus != smtConfig.responseFactory.sat()) {
			return smtConfig.responseFactory.error("The get-model command is only valid immediately after check-sat returned sat");
		}
		try {			
			String s = solverProcess.sendAndListen("(eval ",translate(sexpr),")\n");
			s = s.trim();
			pushes++; // FIXME
			checkSatStatus = null;
			return smtConfig.responseFactory.stringLiteral(s);			
		} catch (IVisitor.VisitorException e) {
			return smtConfig.responseFactory.error("Failed to evaluate expression: " + e + " " + sexpr);
		} catch (Exception e) {
			return smtConfig.responseFactory.error("Failed to evaluate expression: " + e + " " + sexpr);
		}
	}
	
	@Override
	public IResponse get_assertions() {
		if (!logicSet) {
			return smtConfig.responseFactory.error("The logic must be set before a get-assertions command is issued");
		}
		if (!smtConfig.relax && !Utils.TRUE.equals(get_option(smtConfig.exprFactory.keyword(Utils.INTERACTIVE_MODE)))) {
			return smtConfig.responseFactory.error("The get-assertions command is only valid if :interactive-mode has been enabled");
		}
		try {
			StringBuilder sb = new StringBuilder();
			String s;
			int parens = 0;
			do {
				s = solverProcess.sendAndListen("(get-assertions)\n");
				int p = -1;
				while (( p = s.indexOf('(',p+1)) != -1) parens++;
				p = -1;
				while (( p = s.indexOf(')',p+1)) != -1) parens--;
				sb.append(s.replace('\n',' ').replace("\r",""));
			} while (parens > 0);
			s = sb.toString();
			org.smtlib.sexpr.Parser p = new org.smtlib.sexpr.Parser(smtConfig,new org.smtlib.impl.Pos.Source(s,null));
			List<IExpr> exprs = new LinkedList<IExpr>();
			try {
				if (p.isLP()) {
					p.parseLP();
					while (!p.isRP() && !p.isEOD()) {
						IExpr e = p.parseExpr();
						exprs.add(e);
					}
					if (p.isRP()) {
						p.parseRP();
						if (p.isEOD()) return smtConfig.responseFactory.get_assertions_response(exprs); 
					}
				}
			} catch (Exception e ) {
				// continue - fall through
			}
			return smtConfig.responseFactory.error("Unexpected output from the Z3 solver: " + s);
		} catch (IOException e) {
			return smtConfig.responseFactory.error("IOException while reading Z3 reponse");
		}
	}
	
	//call Z3 solver to get current model
	public IResponse get_model() {
		if (!logicSet) {
			return smtConfig.responseFactory.error("The logic must be set before a get-model command is issued");
		}
		
		if (checkSatStatus != smtConfig.responseFactory.sat()) {
			return smtConfig.responseFactory.error("The get-model command is only valid immediately after check-sat returned sat");
		}
		
		//get the model from Z3 and return it
		try
		{
			StringBuilder sb = new StringBuilder();
			String s;
			int parens = 0;
			do 
			{
				if (parens != 0)
					s = solverProcess.listen();
				else
					s = solverProcess.sendAndListen("(get-model)\n");
				int p = -1;
				while (( p = s.indexOf('(',p+1)) != -1) parens++;
				p = -1;
				while (( p = s.indexOf(')',p+1)) != -1) parens--;
				sb.append(s.replace("\r",""));
			} while (parens > 0);
			s=sb.toString().trim();
			try {			
				return smtConfig.responseFactory.stringLiteral(s);				
			} catch (Exception e ) {
				// continue - fall through
			}
			return smtConfig.responseFactory.error("Unexpected output from the Z3 solver: " + s);
		} catch (IOException e) {
			return smtConfig.responseFactory.error("IOException while reading Z3 reponse");
		}
	}


	@Override
	public IResponse check_sat() {
		IResponse res;
		try {
			if (!logicSet) {
				return smtConfig.responseFactory.error("The logic must be set before a check-sat command is issued");
			}
			String s = solverProcess.sendAndListen("(check-sat)\n");
			
			if (s.contains("unsat")) res = smtConfig.responseFactory.unsat();
			else if (s.contains("sat")) res = smtConfig.responseFactory.sat();
			else res = smtConfig.responseFactory.unknown();
			checkSatStatus = res;
		} catch (IOException e) {
			res = smtConfig.responseFactory.error("Failed to check-sat");
		}
		return res;
	}

	@Override
	public IResponse pop(int number) {
		if (!logicSet) {
			return smtConfig.responseFactory.error("The logic must be set before a pop command is issued");
		}
		if (number < 0) throw new SMT.InternalException("Internal bug: A pop command called with a negative argument: " + number);
		if (number >= pushesStack.size()) return smtConfig.responseFactory.error("The argument to a pop command is too large: " + number + " vs. a maximum of " + (pushesStack.size()-1));
		if (number == 0) return smtConfig.responseFactory.success();
		try {
			checkSatStatus = null;
			int n = number;
			while (n-- > 0) {
				pushes = pushesStack.remove(0);
			}
			return parseResponse(solverProcess.sendAndListen("(pop ",new Integer(number).toString(),")\n"));
		} catch (IOException e) {
			return smtConfig.responseFactory.error("Error writing to Z3 solver: " + e);
		}
	}

	@Override
	public IResponse push(int number) {
		if (!logicSet) {
			return smtConfig.responseFactory.error("The logic must be set before a push command is issued");
		}
		if (number < 0) throw new SMT.InternalException("Internal bug: A push command called with a negative argument: " + number);
		checkSatStatus = null;
		if (number == 0) return smtConfig.responseFactory.success();
		try {
			pushesStack.add(pushes);
			int n = number;
			while (--n > 0) {
				pushesStack.add(0);
			}
			pushes = 0;
			return parseResponse(solverProcess.sendAndListen("(push ",new Integer(number).toString(),")\n"));
		} catch (IOException e) {
			return smtConfig.responseFactory.error("Error writing to Z3 solver: " + e);
		}
	}

	@Override
	public IResponse set_logic(String logicName, /*@Nullable*/ IPos pos) {
		if (smtConfig.verbose != 0) smtConfig.log.logDiag("#set-logic " + logicName);
		if (logicSet) {
			if (!smtConfig.relax) return smtConfig.responseFactory.error("Logic is already set");
			pop(pushesStack.size());
			push(1);
		}
		
		try {
			IResponse resp = parseResponse(solverProcess.sendAndListen("(set-logic ",logicName,")\n"));
			logicSet = true;
			return resp;
		} catch (IOException e) {
			return smtConfig.responseFactory.error("Error writing to Z3 solver: " + e,pos);
		}
	}

	@Override
	public IResponse set_option(IKeyword key, IAttributeValue value) {
		String option = key.value();
		if (Utils.PRINT_SUCCESS.equals(option)) {
			if (!(Utils.TRUE.equals(value) || Utils.FALSE.equals(value))) {
				return smtConfig.responseFactory.error("The value of the " + option + " option must be 'true' or 'false'");
			}
		}
		if (logicSet && Utils.INTERACTIVE_MODE.equals(option)) {
			return smtConfig.responseFactory.error("The value of the " + option + " option must be set before the set-logic command");
		}
		if (Utils.PRODUCE_ASSIGNMENTS.equals(option) || 
				Utils.PRODUCE_PROOFS.equals(option) ||
				Utils.PRODUCE_UNSAT_CORES.equals(option) ||
				Utils.PRODUCE_MODELS.equals(option)) {
			if (logicSet) return smtConfig.responseFactory.error("The value of the " + option + " option must be set before the set-logic command");
		}
		if (Utils.VERBOSITY.equals(option)) {
			IAttributeValue v = options.get(option);
			smtConfig.verbose = (v instanceof INumeral) ? ((INumeral)v).intValue() : 0;
		} else if (Utils.DIAGNOSTIC_OUTPUT_CHANNEL.equals(option)) {
			// Actually, v should never be anything but IStringLiteral - that should
			// be checked during parsing
			String name = (value instanceof IStringLiteral)? ((IStringLiteral)value).value() : "stderr";
			if (name.equals("stdout")) {
				smtConfig.log.diag = System.out;
			} else if (name.equals("stderr")) {
				smtConfig.log.diag = System.err;
			} else {
				try {
					FileOutputStream f = new FileOutputStream(name,true); // true -> append
					smtConfig.log.diag = new PrintStream(f);
				} catch (java.io.IOException e) {
					return smtConfig.responseFactory.error("Failed to open or write to the diagnostic output " + e.getMessage(),value.pos());
				}
			}
		} else if (Utils.REGULAR_OUTPUT_CHANNEL.equals(option)) {
			// Actually, v should never be anything but IStringLiteral - that should
			// be checked during parsing
			String name = (value instanceof IStringLiteral)?((IStringLiteral)value).value() : "stdout";
			if (name.equals("stdout")) {
				smtConfig.log.out = System.out;
			} else if (name.equals("stderr")) {
				smtConfig.log.out = System.err;
			} else {
				try {
					FileOutputStream f = new FileOutputStream(name,true); // append
					smtConfig.log.out = new PrintStream(f);
				} catch (java.io.IOException e) {
					return smtConfig.responseFactory.error("Failed to open or write to the regular output " + e.getMessage(),value.pos());
				}
			}
		}
		// Save the options on our side as well
		options.put(option,value);

		if (!Utils.PRINT_SUCCESS.equals(option)) {
			try {
				solverProcess.sendAndListen("(set-option ",option," ",value.toString(),")\n");// FIXME - detect errors
			} catch (IOException e) {
				return smtConfig.responseFactory.error("Error writing to Z3 solver: " + e);
			}
		}
		
		return smtConfig.responseFactory.success();
	}

	@Override
	public IResponse get_option(IKeyword key) {
		String option = key.value();
		IAttributeValue value = options.get(option);
		if (value == null) return smtConfig.responseFactory.unsupported();
		return value;
	}

	@Override
	public IResponse get_info(IKeyword key) { // FIXME - use the solver? what types of results?
		IKeyword option = key;
		IAttributeValue lit;
		if (Utils.ERROR_BEHAVIOR.equals(option)) {
			lit = smtConfig.exprFactory.symbol(Utils.CONTINUED_EXECUTION);
		} else if (Utils.NAME.equals(option)) {
			lit = smtConfig.exprFactory.unquotedString(NAME_VALUE);
		} else if (Utils.AUTHORS.equals(option)) {
			lit = smtConfig.exprFactory.unquotedString(AUTHORS_VALUE);
		} else if (Utils.VERSION.equals(option)) {
			lit = smtConfig.exprFactory.unquotedString(VERSION_VALUE);
			
		} else if (Utils.REASON_UNKNOWN.equals(option)) {
			return smtConfig.responseFactory.unsupported();
		} else if (Utils.ALL_STATISTICS.equals(option)) {
			return smtConfig.responseFactory.unsupported();
		} else {
			return smtConfig.responseFactory.unsupported();
		}
		IAttribute<?> attr = smtConfig.exprFactory.attribute(key,lit);
		return smtConfig.responseFactory.get_info_response(attr);
	}
	
	@Override
	public IResponse set_info(IKeyword key, IAttributeValue value) {
		if (Utils.infoKeywords.contains(key)) {
			return smtConfig.responseFactory.error("Setting the value of a pre-defined keyword is not permitted: "+ 
					smtConfig.defaultPrinter.toString(key),key.pos());
		}
		options.put(key.value(),value);
		return smtConfig.responseFactory.success();
	}


	@Override
	public IResponse declare_fun(Ideclare_fun cmd) {
		if (!logicSet) {
			return smtConfig.responseFactory.error("The logic must be set before a declare-fun command is issued");
		}
		try {
			checkSatStatus = null;
			return parseResponse(solverProcess.sendAndListen(translate(cmd),"\n"));
			
		} catch (IOException e) {
			return smtConfig.responseFactory.error("Error writing to Z3 solver: " + e);
		} catch (IVisitor.VisitorException e) {
			return smtConfig.responseFactory.error("Error writing to Z3 solver: " + e);
		}
	}

	@Override
	public IResponse define_fun(Idefine_fun cmd) {
		if (!logicSet) {
			return smtConfig.responseFactory.error("The logic must be set before a define-fun command is issued");
		}
		try {
			checkSatStatus = null;
			return parseResponse(solverProcess.sendAndListen(translate(cmd),"\n"));
		} catch (IOException e) {
			return smtConfig.responseFactory.error("Error writing to Z3 solver: " + e);
		} catch (IVisitor.VisitorException e) {
			return smtConfig.responseFactory.error("Error writing to Z3 solver: " + e);
		}
	}

	@Override
	public IResponse declare_sort(Ideclare_sort cmd) {
		if (!logicSet) {
			return smtConfig.responseFactory.error("The logic must be set before a declare-sort command is issued");
		}
		try {
			checkSatStatus = null;
			return parseResponse(solverProcess.sendAndListen(translate(cmd),"\n"));
		} catch (IOException e) {
			return smtConfig.responseFactory.error("Error writing to Z3 solver: " + e);
		} catch (IVisitor.VisitorException e) {
			return smtConfig.responseFactory.error("Error writing to Z3 solver: " + e);
		}
	}

	@Override
	public IResponse define_sort(Idefine_sort cmd) {
		if (!logicSet) {
			return smtConfig.responseFactory.error("The logic must be set before a define-sort command is issued");
		}
		try {
			checkSatStatus = null;
			return parseResponse(solverProcess.sendAndListen(translate(cmd),"\n"));
		} catch (IOException e) {
			return smtConfig.responseFactory.error("Error writing to Z3 solver: " + e);
		} catch (IVisitor.VisitorException e) {
			return smtConfig.responseFactory.error("Error writing to Z3 solver: " + e);
		}
	}
	
	//return the proof generated by Z3
	@Override 
	public IResponse get_proof() {
		if (!Utils.TRUE.equals(get_option(smtConfig.exprFactory.keyword(Utils.PRODUCE_PROOFS)))) {
			return smtConfig.responseFactory.error("The get-proof command is only valid if :produce-proofs has been enabled");
		}
		if (checkSatStatus != smtConfig.responseFactory.unsat()) {
			return smtConfig.responseFactory.error("The get-proof command is only valid immediately after check-sat returned unsat");
		}
		try {
			return parseResponse(solverProcess.sendAndListen("(get-proof)\n"));
		} catch (IOException e) {
			return smtConfig.responseFactory.error("Error writing to Z3 solver: " + e);
		}
	}

	//return the minimal unsat core returned by Z3
	@Override 
	public IResponse get_unsat_core() {
		if (!Utils.TRUE.equals(get_option(smtConfig.exprFactory.keyword(Utils.PRODUCE_UNSAT_CORES)))) {
			return smtConfig.responseFactory.error("The get-unsat-core command is only valid if :produce-unsat-cores has been enabled");
		}
		if (checkSatStatus != smtConfig.responseFactory.unsat()) {
			return smtConfig.responseFactory.error("The get-unsat-core command is only valid immediately after check-sat returned unsat");
		}
		try {
			return parseResponse(solverProcess.sendAndListen("(get-unsat-core)\n"));
		} catch (IOException e) {
			return smtConfig.responseFactory.error("Error writing to Z3 solver: " + e);
		}
	}

	//if the model was satisfiable, get assignment of values from Z3
	@Override 
	public IResponse get_assignment() {
		if (!Utils.TRUE.equals(get_option(smtConfig.exprFactory.keyword(Utils.PRODUCE_ASSIGNMENTS)))) {
			return smtConfig.responseFactory.error("The get-assignment command is only valid if :produce-assignments has been enabled");
		}
		if (checkSatStatus != smtConfig.responseFactory.sat() && checkSatStatus != smtConfig.responseFactory.unknown()) {
			return smtConfig.responseFactory.error("The get-assignment command is only valid immediately after check-sat returned sat or unknown");
		}
		try {
			return parseResponse(solverProcess.sendAndListen("(get-assignment)\n"));
		} catch (IOException e) {
			return smtConfig.responseFactory.error("Error writing to Z3 solver: " + e);
		}
	}

	@Override 
	public IResponse get_value(IExpr... terms) {
		// FIXME - do we really want to call get-option here? it involves going to the solver?
		if (!Utils.TRUE.equals(get_option(smtConfig.exprFactory.keyword(Utils.PRODUCE_MODELS)))) {
			return smtConfig.responseFactory.error("The get-value command is only valid if :produce-models has been enabled");
		}
		if (!smtConfig.responseFactory.sat().equals(checkSatStatus) && !smtConfig.responseFactory.unknown().equals(checkSatStatus)) {
			return smtConfig.responseFactory.error("A get-value command is valid only after check-sat has returned sat or unknown");
		}
		try {
			solverProcess.sendNoListen("(get-value (");
			for (IExpr e: terms) {
				solverProcess.sendNoListen(" ",translate(e));
			}
			String r = solverProcess.sendAndListen("))\n");
			IResponse response = parseResponse(r);

			return response;
		} catch (IOException e) {
			return smtConfig.responseFactory.error("Error writing to Z3 solver: " + e);
		} catch (IVisitor.VisitorException e) {
			return smtConfig.responseFactory.error("Error writing to Z3 solver: " + e);
		}
	}

	public class Translator extends Printer { 
		
		public Translator(Writer w) { super(w); }

		@Override
		public Void visit(IFcnExpr e) throws IVisitor.VisitorException {
			// Only - for >=2 args is not correctly done, but we can't delegate to translateSMT because it might be a sub-expression.
			Iterator<IExpr> iter = e.args().iterator();
			if (!iter.hasNext()) throw new VisitorException("Did not expect an empty argument list",e.pos());
			IQualifiedIdentifier fcn = e.head();
			int length = e.args().size();
			if (length > 2 && (fcn instanceof IIdentifier) && fcn.toString().equals("-")) {
				leftassoc(fcn.toString(),length,iter);
			} else {
				super.visit(e);
			}
			return null;
		}

		//@ requires iter.hasNext();
		//@ requires length > 0;
		protected <T extends IExpr> void leftassoc(String fcnname, int length, Iterator<T> iter ) throws IVisitor.VisitorException {
			if (length == 1) {
				iter.next().accept(this);
			} else {
				try {
					w.append("(");
					w.append(fcnname);
					w.append(" ");
					leftassoc(fcnname,length-1,iter);
					w.append(" ");
					iter.next().accept(this);
					w.append(")");
				} catch (IOException ex) {
					throw new IVisitor.VisitorException(ex,null); // FIXME - null ?
				}
			}
		}

		//@ requires iter.hasNext();
		protected <T extends IExpr> void rightassoc(String fcnname, Iterator<T> iter ) throws IVisitor.VisitorException {
			T n = iter.next();
			if (!iter.hasNext()) {
				n.accept(this);
			} else {
				try {
					w.append("(");
					w.append(fcnname);
					w.append(" ");
					n.accept(this);
					w.append(" ");
					rightassoc(fcnname,iter);
					w.append(")");
				} catch (IOException ex) {
					throw new IVisitor.VisitorException(ex,null); // FIXME - null ?
				}
			}
		}

		
		//@ requires iter.hasNext();
		//@ requires length > 0;
		protected <T extends IAccept> void chainable(String fcnname, Iterator<T> iter ) throws IVisitor.VisitorException {
			try {
				w.append("(and ");
				T left = iter.next();
				while (iter.hasNext()) {
					w.append("(");
					w.append(fcnname);
					w.append(" ");
					left.accept(this);
					w.append(" ");
					(left=iter.next()).accept(this);
					w.append(")");
				}
				w.append(")");
			} catch (IOException ex) {
				throw new IVisitor.VisitorException(ex,null); // FIXME - null ?
			}
		}
	}
}
