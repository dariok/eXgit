package org.exist.xquery.modules.exgit;

import java.util.List;
import java.util.Map;

import org.exist.xquery.AbstractInternalModule;
import org.exist.xquery.FunctionDef;

public class Exgit extends AbstractInternalModule {
	
	public final static String NAMESPACE_URI = "http://exist-db.org/xquery/exgits";
	
	public final static String PREFIX = "exgit";
    public final static String INCLUSION_DATE = "2017-11-11";
    public final static String RELEASED_IN_VERSION = "eXist-3.4";
	
    private final static FunctionDef[] functions = {
    	    new FunctionDef(GitFunctions.signature[0], GitFunctions.class)
    	};
    
    public Exgit(Map<String, List<?>> parameters) {
		super(functions, parameters);
	}
	
	@Override
	public String getDefaultPrefix() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getDescription() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getNamespaceURI() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getReleaseVersion() {
		// TODO Auto-generated method stub
		return null;
	}

}
