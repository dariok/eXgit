package org.exist.xquery.modules.exgit;

import org.eclipse.jgit.api.Git;

import org.xmldb.api.base.*;
import org.xmldb.api.modules.*;
import org.xmldb.api.*;
import javax.xml.transform.OutputKeys;
import org.exist.xmldb.EXistResource;

import org.exist.dom.QName;
import org.exist.xquery.BasicFunction;
import org.exist.xquery.Cardinality;
import org.exist.xquery.Dependency;
import org.exist.xquery.ErrorCodes.ErrorCode;
//import org.exist.xquery.Function;
import org.exist.xquery.FunctionSignature;
//import org.exist.xquery.Profiler;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.functions.xmldb.XMLDBGetChildCollections;
import org.exist.xquery.functions.xmldb.XMLDBGetChildResources;
import org.exist.xquery.value.DoubleValue;
import org.exist.xquery.value.FunctionParameterSequenceType;
import org.exist.xquery.value.FunctionReturnSequenceType;
import org.exist.xquery.value.IntegerValue;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.SequenceIterator;
import org.exist.xquery.value.SequenceType;
import org.exist.xquery.value.StringValue;
import org.exist.xquery.value.Type;
import org.exist.xquery.value.ValueSequence;

public class GitFunctions extends BasicFunction {
	private static String URI = "xmldb:exist://localhost:8080/exist/xmlrpc";

	public final static FunctionSignature signature[] = {
			new FunctionSignature(new QName("commit", Exgit.NAMESPACE_URI, Exgit.PREFIX), "Execute a git commit.",
					new SequenceType[] { new FunctionParameterSequenceType("message", Type.STRING,
							Cardinality.EXACTLY_ONE, "The commit message.") },
					new FunctionReturnSequenceType(Type.DOUBLE, Cardinality.EXACTLY_ONE, "the commit hash.")),
			new FunctionSignature(new QName("push", Exgit.NAMESPACE_URI, Exgit.PREFIX), "Execute git push.", null,
					new FunctionReturnSequenceType(Type.STRING, Cardinality.EXACTLY_ONE, "the reply.")) };

	private Sequence result;

	public GitFunctions(XQueryContext context, FunctionSignature signature) {
		super(context, signature);
	}

	public Sequence eval(Sequence[] args, Sequence contextSequence) throws XPathException {
		String functionName = getSignature().getName().getLocalPart();
		result = null;

		// initialize database driver
		try {
			Database database = (Database) org.exist.xmldb.DatabaseImpl.class.newInstance();
			database.setProperty("create-database", "true");
			DatabaseManager.registerDatabase(database);
		} catch (Exception dbe) {
			throw new XPathException(new ErrorCode("exgit01", "XMLDB error"), dbe.toString());
		}

		Collection col;
		//XMLResource res;

		switch (functionName) {
		case "commit":
			try {
				col = DatabaseManager.getCollection(URI + args[0].toString());
			} catch (Exception e2) {
				throw new XPathException(new ErrorCode("exgit02", "Error getting coll.: " + e2.toString()), e2.toString());
			}
			
			/*String[] listR;
			try {
				listR = col.listResources();
			} catch (XMLDBException e1) {
				throw new XPathException(new ErrorCode("exgit02a", e1.toString()), e1.toString());
			}*/
			
			try {
				result.add(new StringValue(args.toString()));
				result.add(new StringValue(col.getName()));
				result.add(new IntegerValue(col.getResourceCount()));
			} catch (XMLDBException e) {
				throw new XPathException(new ErrorCode("exgit03", e.toString()), e.toString());
			}
			
			break;
		case "push":
			result.add(new StringValue("okay"));
			break;
		default:
			throw new XPathException(new ErrorCode("E01", "function not found"),
					"The requested function was not found in this module");
		}

		return result;
	}
}
