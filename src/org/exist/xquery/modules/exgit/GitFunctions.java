package org.exist.xquery.modules.exgit;

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
import org.exist.xquery.value.DoubleValue;
import org.exist.xquery.value.FunctionParameterSequenceType;
import org.exist.xquery.value.FunctionReturnSequenceType;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.SequenceIterator;
import org.exist.xquery.value.SequenceType;
import org.exist.xquery.value.StringValue;
import org.exist.xquery.value.Type;
import org.exist.xquery.value.ValueSequence;

public class GitFunctions extends BasicFunction {
	public final static FunctionSignature signature[] = {
			new FunctionSignature(
			    new QName("commit", Exgit.NAMESPACE_URI, Exgit.PREFIX),
			    "Execute a git commit.",
			    new SequenceType[] { 
			        new FunctionParameterSequenceType(
			        		"message",
			        		Type.STRING,
			        		Cardinality.EXACTLY_ONE,
			        		"The commit message."
			        	)
			    },
			    new FunctionReturnSequenceType(
			    		Type.DOUBLE,
			    		Cardinality.EXACTLY_ONE,
			    		"the commit hash.")
			),
			new FunctionSignature(
					new QName("push", Exgit.NAMESPACE_URI, Exgit.PREFIX),
					"Execute git push.",
					null,
					new FunctionReturnSequenceType(
							Type.STRING,
							Cardinality.EXACTLY_ONE,
							"the reply."
					)
			)
	};

	public GitFunctions(XQueryContext context, FunctionSignature signature) {
        super(context, signature);
    }

	public Sequence eval(Sequence[] args, Sequence contextSequence) throws XPathException {
		String functionName = getSignature().getName().getLocalPart();
		Sequence result;
		
		switch (functionName) {
		case "commit":
			result = new DoubleValue(3);
			break;
		case "push":
			result = new StringValue("okay");
			break;
		default:
			throw new XPathException(new ErrorCode("E01", "function not found"), "The requested function was not found in this module");
		}
		
		return result;
	}
}
