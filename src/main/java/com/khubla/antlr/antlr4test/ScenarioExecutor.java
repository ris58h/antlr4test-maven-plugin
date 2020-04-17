/*
 [The "BSD license"]
 Copyright (c) 2020 Gil Cesar Faria
 All rights reserved.

 Redistribution and use in source and binary forms, with or without
 modification, are permitted provided that the following conditions
 are met:
 1. Redistributions of source code must retain the above copyright
    notice, this list of conditions and the following disclaimer.
 2. Redistributions in binary form must reproduce the above copyright
    notice, this list of conditions and the following disclaimer in the
    documentation and/or other materials provided with the distribution.
 3. The name of the author may not be used to endorse or promote products
    derived from this software without specific prior written permission.

 THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.khubla.antlr.antlr4test;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.List;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.tree.Trees;
import org.bitbucket.cowwoc.diffmatchpatch.DiffMatchPatch;
import org.codehaus.plexus.util.FileUtils;

public class ScenarioExecutor {

	private Scenario scenario = null;

	public ScenarioExecutor(Scenario scenario) {
		this.scenario = scenario;
	}

	public void testGrammars() throws Exception {
		/*
		 * iterate examples
		 */
		final List<File> exampleFiles = FileUtil.getAllFiles(scenario.getExampleFilesDir().getAbsolutePath());
		if (null != exampleFiles) {
			for (final File file : exampleFiles) {
				/*
				 * test grammar
				 */
				if ((!file.getName().endsWith(GrammarTestMojo.ERRORS_SUFFIX))
						&& (!file.getName().endsWith(GrammarTestMojo.TREE_SUFFIX))) {
					/*
					 * file extension
					 */
					if ((scenario.getTestFileExtension() == null) || ((scenario.getTestFileExtension() != null)
							&& (file.getName().endsWith(scenario.getTestFileExtension())))) {
						testGrammar(scenario, file);
					}
				}
				/*
				 * gc
				 */
				System.gc();
			}
		}
	}

	/**
	 * test a single grammar
	 */
	private void testGrammar(Scenario scenario, File grammarFile) throws Exception {
		/*
		 * figure out class names
		 */
		String nn = scenario.getGrammarName();
		if (null != scenario.getPackageName() && !"".equals(scenario.getPackageName())) {
			nn = scenario.getPackageName() + "." + scenario.getGrammarName();
		}
		final String lexerClassName = nn + "Lexer";
		final String parserClassName = nn + "Parser";
		if (scenario.isVerbose()) {
			System.out.println("Lexer classname is: " + lexerClassName);
			System.out.println("Parser classname is: " + parserClassName);
		}
		/*
		 * classloader
		 */
		final ClassLoader classLoader = scenario.getClassLoader();
		/*
		 * get the classes we need
		 */
		final Class<? extends Lexer> lexerClass = classLoader.loadClass(lexerClassName).asSubclass(Lexer.class);
		final Class<? extends Parser> parserClass = classLoader.loadClass(parserClassName).asSubclass(Parser.class);
		Class<? extends GrammarInitializer> initializerClass = null;
		String grammarInitializer = scenario.getGrammarInitializer();
		if (grammarInitializer != null && !"".equals(grammarInitializer)) {
			initializerClass = classLoader.loadClass(grammarInitializer).asSubclass(GrammarInitializer.class);
		}
		/*
		 * get ctors
		 */
		final Constructor<?> lexerConstructor = lexerClass.getConstructor(CharStream.class);
		final Constructor<?> parserConstructor = parserClass.getConstructor(TokenStream.class);
		System.out.println("Parsing :" + grammarFile.getAbsolutePath());
		CharStream antlrFileStream;
		if (scenario.getCaseInsensitiveType() == CaseInsensitiveType.None) {
			antlrFileStream = CharStreams.fromPath(grammarFile.toPath(), Charset.forName(scenario.getFileEncoding()));
		} else {
			antlrFileStream = new AntlrCaseInsensitiveFileStream(grammarFile.getAbsolutePath(),
					scenario.getFileEncoding(), scenario.getCaseInsensitiveType());
		}
		/*
		 * build lexer
		 */
		final AssertErrorsErrorListener assertErrorsErrorListener = new AssertErrorsErrorListener();
		Lexer lexer = (Lexer) lexerConstructor.newInstance(antlrFileStream);
		lexer.addErrorListener(assertErrorsErrorListener);
		/*
		 * build token stream
		 */

		final CommonTokenStream tokens = new CommonTokenStream(lexer);
		/*
		 * build parser
		 */
		Parser parser = (Parser) parserConstructor.newInstance(tokens);
		parser.addErrorListener(assertErrorsErrorListener);
		/*
		 * initializer lexer and parser
		 */
		if (initializerClass != null) {
			System.out.println(initializerClass.getName());
			GrammarInitializer initializer = (GrammarInitializer) initializerClass.newInstance();
			initializer.initialize(lexer, parser);
		}

		/*
		 * show tokens
		 */
		if (scenario.isVerbose()) {
			tokens.fill();
			for (final Object tok : tokens.getTokens()) {
				System.out.println(tok);
			}
		}

		final Method method = parserClass.getMethod(scenario.getEntryPoint());
		ParserRuleContext parserRuleContext = (ParserRuleContext) method.invoke(parser);
		assertErrorsErrorListener.assertErrors(new File(grammarFile.getAbsolutePath() + GrammarTestMojo.ERRORS_SUFFIX),
				scenario.getFileEncoding());
		/*
		 * show the tree
		 */
		if (scenario.isShowTree()) {
			final String lispTree = Trees.toStringTree(parserRuleContext, parser);
			System.out.println(lispTree);
		}
		/*
		 * check syntax
		 */
		final File treeFile = new File(grammarFile.getAbsolutePath() + GrammarTestMojo.TREE_SUFFIX);
		if (treeFile.exists()) {
			final String lispTree = Trees.toStringTree(parserRuleContext, parser);
			if (null != lispTree) {
				final String treeFileData = FileUtils.fileRead(treeFile, scenario.getFileEncoding());
				if (null != treeFileData) {
					if (0 != treeFileData.compareTo(lispTree)) {
						StringBuilder sb = new StringBuilder(
								"Parse tree does not match '" + treeFile.getName() + "'. Differences: ");
						for (DiffMatchPatch.Diff diff : new DiffMatchPatch().diffMain(treeFileData, lispTree)) {
							sb.append(diff.toString());
							sb.append(", ");
						}
						throw new Exception(sb.toString());
					} else {
						System.out.println(
								"Parse tree for '" + grammarFile.getName() + "' matches '" + treeFile.getName() + "'");
					}
				}
			}
		}
		/*
		 * yup
		 */
		parser = null;
		lexer = null;
		parserRuleContext = null;
		antlrFileStream = null;
	}

}