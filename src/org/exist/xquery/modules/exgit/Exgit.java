package org.exist.xquery.modules.exgit;

import java.util.List;
import java.util.Map;

import org.exist.xquery.AbstractInternalModule;
import org.exist.xquery.FunctionDef;

public class Exgit extends AbstractInternalModule {
	public final static String NAMESPACE_URI		= "http://exist-db.org/xquery/exgit";
	public final static String PREFIX				= "exgit";
	public final static String INCLUSION_DATE		= "2017-11-11";
	public final static String RELEASED_IN_VERSION	= "eXist-3.4";

	private final static FunctionDef[] functions = {
			new FunctionDef(GitFunctions.signature[0], GitFunctions.class),
			new FunctionDef(GitFunctions.signature[1], GitFunctions.class),
			new FunctionDef(GitFunctions.signature[2], GitFunctions.class),
			new FunctionDef(GitFunctions.signature[3], GitFunctions.class),
			new FunctionDef(GitFunctions.signature[4], GitFunctions.class),
			new FunctionDef(GitFunctions.signature[5], GitFunctions.class),
			new FunctionDef(GitFunctions.signature[6], GitFunctions.class),
			new FunctionDef(GitFunctions.signature[7], GitFunctions.class),
			new FunctionDef(GitFunctions.signature[8], GitFunctions.class),
			new FunctionDef(GitFunctions.signature[9], GitFunctions.class),
			new FunctionDef(GitFunctions.signature[10], GitFunctions.class),
			new FunctionDef(GitFunctions.signature[11], GitFunctions.class)
	};

	public Exgit(Map<String, List<?>> parameters) {
		super(functions, parameters);
	}

	public String getNamespaceURI() {
		return NAMESPACE_URI;
	}

	public String getDefaultPrefix() {
		return PREFIX;
	}

	@Override
	public String getDescription() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getReleaseVersion() {
		// TODO Auto-generated method stub
		return null;
	}

}
