package org.exist.xquery.modules.exgit;

import org.exist.dom.QName;
import org.exist.xquery.BasicFunction;
import org.exist.xquery.Cardinality;
import org.exist.xquery.Dependency;
import org.exist.xquery.FunctionSignature;
import org.exist.xquery.Profiler;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.value.DoubleValue;
import org.exist.xquery.value.FunctionParameterSequenceType;
import org.exist.xquery.value.FunctionReturnSequenceType;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.SequenceType;
import org.exist.xquery.value.Type;

public class GitFunctions extends BasicFunction {
	public final static FunctionSignature[] signature = {
			new FunctionSignature(
				new QName ( "commit", Exgit.NAMESPACE_URI, Exgit.PREFIX ),
				"git commit",
				new SequenceType[] {
					new FunctionParameterSequenceType("message", Type.STRING, Cardinality
							.EXACTLY_ONE, "The commit message")
				},
				new FunctionReturnSequenceType(Type.STRING,
						Cardinality.EXACTLY_ONE,
						"the commit hash"))
	};

	public GitFunctions(XQueryContext context, FunctionSignature signature) {
		super(context, signature);
	}

	public Sequence eval(Sequence[] args, Sequence contextSequence) throws XPathException {
		Sequence result;
		result = new DoubleValue(0);
		return result;
	}
}
