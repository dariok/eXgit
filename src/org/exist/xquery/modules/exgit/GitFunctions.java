package org.exist.xquery.modules.exgit;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.BOMInputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.api.errors.UnmergedPathsException;
import org.eclipse.jgit.api.errors.WrongRepositoryStateException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.ObjectStream;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.util.FS;
import org.exist.EXistException;
import org.exist.collections.Collection;
import org.exist.collections.IndexInfo;
import org.exist.dom.QName;
import org.exist.dom.memtree.MemTreeBuilder;
import org.exist.dom.persistent.BinaryDocument;
import org.exist.dom.persistent.DocumentImpl;
import org.exist.dom.persistent.LockedDocument;
import org.exist.security.PermissionDeniedException;
import org.exist.storage.BrokerPool;
import org.exist.storage.lock.Lock.LockMode;
import org.exist.storage.serializers.Serializer;
import org.exist.storage.txn.Txn;
import org.exist.util.LockException;
import org.exist.xmldb.XmldbURI;
import org.exist.xquery.BasicFunction;
import org.exist.xquery.Cardinality;
import org.exist.xquery.ErrorCodes.ErrorCode;
import org.exist.xquery.FunctionSignature;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.value.BooleanValue;
import org.exist.xquery.value.FunctionParameterSequenceType;
import org.exist.xquery.value.FunctionReturnSequenceType;
import org.exist.xquery.value.NodeValue;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.SequenceIterator;
import org.exist.xquery.value.SequenceType;
import org.exist.xquery.value.StringValue;
import org.exist.xquery.value.Type;
import org.exist.xquery.value.ValueSequence;
import org.xml.sax.SAXException;


public class GitFunctions extends BasicFunction {
	public final static FunctionSignature signature[] = {
		new FunctionSignature(new QName("commit", Exgit.NAMESPACE_URI, Exgit.PREFIX), "Execute a git commit.",
				new SequenceType[] {
						new FunctionParameterSequenceType("repoDir", Type.STRING, Cardinality.EXACTLY_ONE,
								"The full path to the local git repository"),
						new FunctionParameterSequenceType("message", Type.STRING, Cardinality.EXACTLY_ONE,
								"The commit message.") },
				new FunctionReturnSequenceType(Type.STRING, Cardinality.MANY, "the commit hash.")),
		new FunctionSignature(new QName("commit", Exgit.NAMESPACE_URI, Exgit.PREFIX), "Execute a git commit.",
				new SequenceType[] {
						new FunctionParameterSequenceType("repoDir", Type.STRING, Cardinality.EXACTLY_ONE,
								"The full path to the local git repository"),
						new FunctionParameterSequenceType("message", Type.STRING, Cardinality.EXACTLY_ONE,
								"The commit message."),
						new FunctionParameterSequenceType("authorName", Type.STRING, Cardinality.EXACTLY_ONE,
								"The name to set as author."),
						new FunctionParameterSequenceType("authorMail", Type.STRING, Cardinality.EXACTLY_ONE,
								"The eMail to set for the author.")},
				new FunctionReturnSequenceType(Type.STRING, Cardinality.MANY, "the commit hash.")),
		new FunctionSignature(new QName("push", Exgit.NAMESPACE_URI, Exgit.PREFIX), "Execute git push.",
				new SequenceType[] {
						new FunctionParameterSequenceType("repoDir", Type.STRING, Cardinality.EXACTLY_ONE,
								"The full path to the local git repository"),
						new FunctionParameterSequenceType("remote", Type.STRING, Cardinality.EXACTLY_ONE,
								"The remote."),
						new FunctionParameterSequenceType("username", Type.STRING, Cardinality.EXACTLY_ONE,
								"The user for authentication."),
						new FunctionParameterSequenceType("password", Type.STRING, Cardinality.EXACTLY_ONE,
								"The remote password.") },
				new FunctionReturnSequenceType(Type.STRING, Cardinality.MANY, "the reply.")),
		new FunctionSignature(new QName("export", Exgit.NAMESPACE_URI, Exgit.PREFIX),
				"Write $collection to $repoDir.",
				new SequenceType[] {
						new FunctionParameterSequenceType("repoDir", Type.STRING, Cardinality.EXACTLY_ONE,
								"The full path to the local git repository"),
						new FunctionParameterSequenceType("collection", Type.STRING, Cardinality.EXACTLY_ONE,
								"The collection to export to disk.")},
				new FunctionReturnSequenceType(Type.BOOLEAN, Cardinality.EXACTLY_ONE,
						"true if successful, false otherwise")),
		new FunctionSignature(new QName("pull", Exgit.NAMESPACE_URI, Exgit.PREFIX), "Execute git pull.",
				new SequenceType[] {
						new FunctionParameterSequenceType("repoDir", Type.STRING, Cardinality.EXACTLY_ONE,
								"The full path to the local git repository"),
						new FunctionParameterSequenceType("remote", Type.STRING, Cardinality.EXACTLY_ONE,
								"The remote."),
						new FunctionParameterSequenceType("username", Type.STRING, Cardinality.EXACTLY_ONE,
								"The user for authentication."),
						new FunctionParameterSequenceType("password", Type.STRING, Cardinality.EXACTLY_ONE,
								"The remote password.") },
				new FunctionReturnSequenceType(Type.STRING, Cardinality.EXACTLY_ONE, "the reply.")),
		new FunctionSignature(new QName("import", Exgit.NAMESPACE_URI, Exgit.PREFIX),
				"Import the contents of $repoDir into $collection. Binary files will be ignored.",
				new SequenceType[] {
						new FunctionParameterSequenceType("repoDir", Type.STRING, Cardinality.EXACTLY_ONE,
								"The full path to the local git repository"),
						new FunctionParameterSequenceType("collection", Type.STRING, Cardinality.EXACTLY_ONE,
								"The collection to import from disk.")},
				new FunctionReturnSequenceType(Type.BOOLEAN, Cardinality.EXACTLY_ONE,
						"true if successful, false otherwise")),
		new FunctionSignature(new QName("clone", Exgit.NAMESPACE_URI, Exgit.PREFIX),
				"Clone $repo to $repoDir.",
				new SequenceType[] {
						new FunctionParameterSequenceType("repo", Type.STRING, Cardinality.EXACTLY_ONE,
								"The full URL for the repo to be cloned."),
						new FunctionParameterSequenceType("repoDir", Type.STRING, Cardinality.EXACTLY_ONE,
								"The full path to the local folder to clone into.")},
				new FunctionReturnSequenceType(Type.STRING, Cardinality.EXACTLY_ONE,
						"The clone result.")),
		new FunctionSignature(new QName("clone", Exgit.NAMESPACE_URI, Exgit.PREFIX),
				"Clone $repo to $repoDir.",
				new SequenceType[] {
						new FunctionParameterSequenceType("repo", Type.STRING, Cardinality.EXACTLY_ONE,
								"The full URL for the repo to be cloned."),
						new FunctionParameterSequenceType("repoDir", Type.STRING, Cardinality.EXACTLY_ONE,
								"The full path to the local folder to clone into."),
						new FunctionParameterSequenceType("branch", Type.STRING, Cardinality.EXACTLY_ONE,
								"The branch to clone (e.g. 'refs/heads/development').")},
				new FunctionReturnSequenceType(Type.STRING, Cardinality.EXACTLY_ONE,
						"The clone result.")),
		new FunctionSignature(new QName("clone", Exgit.NAMESPACE_URI, Exgit.PREFIX),
				"Clone $branch from $repo to $repoDir with as $username with $password.",
				new SequenceType[] {
						new FunctionParameterSequenceType("repo", Type.STRING, Cardinality.EXACTLY_ONE,
								"The full URL for the repo to be cloned."),
						new FunctionParameterSequenceType("repoDir", Type.STRING, Cardinality.EXACTLY_ONE,
								"The full path to the local folder to clone into."),
						new FunctionParameterSequenceType("branch", Type.STRING, Cardinality.EXACTLY_ONE,
								"The branch to clone (e.g. 'refs/heads/development')."),
						new FunctionParameterSequenceType("username", Type.STRING, Cardinality.EXACTLY_ONE,
								"The user for authentication."),
						new FunctionParameterSequenceType("password", Type.STRING, Cardinality.EXACTLY_ONE,
								"The remote password.")},
				new FunctionReturnSequenceType(Type.STRING, Cardinality.EXACTLY_ONE,
						"The clone result.")),
		new FunctionSignature(new QName("checkout", Exgit.NAMESPACE_URI, Exgit.PREFIX),
				"Checkout a specific commit.",
				new SequenceType[] {
						new FunctionParameterSequenceType("repoDir", Type.STRING, Cardinality.EXACTLY_ONE,
								"The full path to the local git repository."),
						new FunctionParameterSequenceType("commit", Type.STRING, Cardinality.EXACTLY_ONE,
								"The hash of the commit or the full tag (i.e. 'refs/tags/my-tag') to checkout.")},
				new FunctionReturnSequenceType(Type.STRING, Cardinality.EXACTLY_ONE,
						"The result.")),
		new FunctionSignature(new QName("tags", Exgit.NAMESPACE_URI, Exgit.PREFIX),
				"List all tags in a repo.",
				new SequenceType[] {
						new FunctionParameterSequenceType("repoDir", Type.STRING, Cardinality.EXACTLY_ONE,
								"The full path to the local git repository.")},
				new FunctionReturnSequenceType(Type.ELEMENT, Cardinality.MANY,
						"The tags in the repository.")),
		new FunctionSignature(new QName("info", Exgit.NAMESPACE_URI, Exgit.PREFIX),
				"Return information about a commit.",
				new SequenceType[] {
						new FunctionParameterSequenceType("repoDir", Type.STRING, Cardinality.EXACTLY_ONE,
								"The full path to the local git repository."),
						new FunctionParameterSequenceType("commit", Type.STRING, Cardinality.EXACTLY_ONE,
								"The commit's ID.")},
				new FunctionReturnSequenceType(Type.ELEMENT, Cardinality.MANY,
						"Structured information.")),
		new FunctionSignature(new QName("exportFiles", Exgit.NAMESPACE_URI, Exgit.PREFIX),
				"Write $collection to $repoDir.",
				new SequenceType[] {
						new FunctionParameterSequenceType("repoDir", Type.STRING, Cardinality.EXACTLY_ONE,
								"The full path to the local git repository"),
						new FunctionParameterSequenceType("collection", Type.STRING, Cardinality.EXACTLY_ONE,
								"The collection to export to disk."),
						new FunctionParameterSequenceType("resources", Type.ANY_URI, Cardinality.ONE_OR_MORE,
								"A sequence of relative file paths.")},
				new FunctionReturnSequenceType(Type.BOOLEAN, Cardinality.EXACTLY_ONE,
						"true if successful, false otherwise")),
		new FunctionSignature(new QName("exportFiltered", Exgit.NAMESPACE_URI, Exgit.PREFIX),
				"Write $collection to $repoDir.",
				new SequenceType[] {
						new FunctionParameterSequenceType("repoDir", Type.STRING, Cardinality.EXACTLY_ONE,
								"The full path to the local git repository"),
						new FunctionParameterSequenceType("collection", Type.STRING, Cardinality.EXACTLY_ONE,
								"The collection to export to disk."),
						new FunctionParameterSequenceType("regex", Type.STRING, Cardinality.EXACTLY_ONE,
								"A regular expression to filter file names.")},
				new FunctionReturnSequenceType(Type.BOOLEAN, Cardinality.EXACTLY_ONE,
						"true if successful, false otherwise")),
		new FunctionSignature(new QName("memImport", Exgit.NAMESPACE_URI, Exgit.PREFIX),
				"Import last commit in master from $remote into $collection.",
				new SequenceType[] {
						new FunctionParameterSequenceType("remote", Type.STRING, Cardinality.EXACTLY_ONE,
								"The full path to the remote git repository"),
						new FunctionParameterSequenceType("collection", Type.STRING, Cardinality.EXACTLY_ONE,
								"The collection to import to.")},
				new FunctionReturnSequenceType(Type.STRING, Cardinality.ZERO_OR_MORE,
						"the files that have been imported."))
		};

	public GitFunctions(XQueryContext context, FunctionSignature signature) {
		super(context, signature);
	}

	public Sequence eval(Sequence[] args, Sequence contextSequence) throws XPathException {
		String functionName = getSignature().getName().getLocalPart();
		ValueSequence result = new ValueSequence();
		
		/* TODO somehow store within the collection that it belongs to a git repo and which repo that is
		 * and where that repo  is to be found on the file system
		 */
		// TODO load configuration from a file in the database
		// Should this be a configuration file in /db/system or should it be stored in the collection?
		
		// TODO function to initialize the git repo
		
		switch (functionName) {
		case "checkout":
		{
			String local = getDir(args[0].toString()).toString();
			if (!isRepo(new File(local)))
				throw new XPathException(new ErrorCode("exgit030f", "Not a git repo"),
						"The selected path is not a git repo: " + local);
			String ref = args[1].toString();
			
			try (Git git = getRepo(local)) {
				git.checkout().setName(ref).call();
			} catch (RefNotFoundException rnfe) {
				throw new XPathException(new ErrorCode("exgit360", "Requested ref not found in repo"),
					"The requested ref " + ref + " was not found in repo " + local
					+ ": " + rnfe.getLocalizedMessage());
			} catch (org.eclipse.jgit.api.errors.CheckoutConflictException cce) {
				throw new XPathException(new ErrorCode("exgit363", "Checkout conflict"),
					"Conflict checking out ref " + ref + " in repo " + local
					+ ": " + cce.getLocalizedMessage());
			} catch (GitAPIException e) {
				throw new XPathException(new ErrorCode("exgit369", "Git API Error on checkout"),
					"General git API error checking out ref " + ref + " in repo " + local
					+ ": " + e.getLocalizedMessage());
			}
			
			break;
		}
		case "clone":
		{
			String repo = args[0].toString();
			String local = getDir(args[1].toString()).toString();
			
			CloneCommand cc = Git.cloneRepository()
					.setURI(repo)
					.setDirectory(new File(local))
					.setCloneSubmodules(true);
			
			if (args.length == 3)
				cc.setBranchesToClone(Collections.singleton(args[2].toString()))
						.setBranch(args[2].toString());
			if (args.length == 5)
				cc.setBranchesToClone(Collections.singleton(args[2].toString()))
						.setBranch(args[2].toString())
						.setCredentialsProvider(new UsernamePasswordCredentialsProvider(args[3].toString(), args[4].toString()));
			
			try (Git git = cc.call()) {
				result.add(new StringValue("Cloned into: " + local));
				result.add(new StringValue("Result: " + git.toString()));
			} catch (InvalidRemoteException ire) {
				throw new XPathException(new ErrorCode("exgit353", "Invalid remote"),
					"Error cloning " + repo + " into " + local + ": " + ire.getLocalizedMessage());
			} catch (GitAPIException e) {
				throw new XPathException(new ErrorCode("exgit359", "Git API Error on clone"),
					"General error cloning " + repo + " into " + local + ": " + e.getLocalizedMessage());
			}
			
			break;
		}
		case "commit":
		{
			String message = args[1].toString();
			String local = getDir(args[0].toString()).toString();
			if (!isRepo(new File(local)))
				throw new XPathException(new ErrorCode("exgit030e", "Not a git repo"),
						"The selected path is not a git repo: " + local);
			Git git = getRepo(local);
			
			String add;	// added files
			String del; // deleted files
			String chg;	// changed files
			String mod;	// modified files
			try {
				// make sure that new files are added; cf. #4
				@SuppressWarnings("unused")
				DirCache addC = git.add().addFilepattern(".").call();
				
				Status stat = git.status().call();
				chg = stat.getChanged().toString();
				mod = stat.getModified().toString();
				add = stat.getAdded().toString();
				del = stat.getRemoved().toString();
			} catch (GitAPIException e) {
				throw new XPathException(new ErrorCode("exgit309a", "Git API Error on add"),
						"Error adding files to " + local + ": " + e.getLocalizedMessage());
			} 
			
			CommitCommand comm = git.commit().setAll(true).setMessage(message);
			if (args.length == 3)
				comm.setAuthor(args[2].toString(), args[3].toString());
			
			RevCommit c;
			try {
				c = comm.call();
			} catch (WrongRepositoryStateException wrse) {
				throw new XPathException(new ErrorCode("exgit303", "Wrong repository state"),
					"Wrong repository state committing to " + local + ": " + wrse.getLocalizedMessage());
			} catch (NoHeadException nhe) {
				throw new XPathException(new ErrorCode("exgit304", "No Head"),
					"No head when committing to " + local + ": " + nhe.getLocalizedMessage());
			} catch (UnmergedPathsException upe) {
				throw new XPathException(new ErrorCode("exgit305", "Unmerged data"),
					"Unmerged data when committing to " + local + ": " + upe.getLocalizedMessage());
			} catch (GitAPIException e) {
				throw new XPathException(new ErrorCode("exgit309b", "Git API Error on add"),
					"Error adding files to " + local + ": " + e.getLocalizedMessage());
			}
			
			result.add(new StringValue("Path to local repo: " + local));
			result.add(new StringValue("Changed: " + chg));
			result.add(new StringValue("Modified: " + mod));
			result.add(new StringValue("Added: " + add));
			result.add(new StringValue("removed: " + del));
			result.add(new StringValue("Revision: " + c.getId().toString()));
			result.add(new StringValue("Author: " + c.getAuthorIdent().toString()));
			
			break;
		}
		case "push":
		{
			String remote = args[1].toString();
			String username = args[2].toString();
			String password = args[3].toString();
			String local = getDir(args[0].toString()).toString();
			
			if (!isRepo(new File(local)))
				throw new XPathException(new ErrorCode("exgit030d", "Not a git repo"),
						"The selected path is not a git repo: " + local);
			
			Iterable<PushResult> p;
			try (Git git = getRepo(local)) {
				p = git.push().setRemote(remote)
						.setCredentialsProvider(new UsernamePasswordCredentialsProvider(username, password))
						.call();
			} catch (InvalidRemoteException ire) {
				throw new XPathException(new ErrorCode("exgit313", "Invalid remote"),
					"Invalid remote when pushing to " + remote + ": " + ire.getLocalizedMessage());
			} catch (TransportException te) {
				throw new XPathException(new ErrorCode("exgit314", "Transport Exception"),
					"Transport error when pushing to " + remote + te.getLocalizedMessage());
			} catch (GitAPIException e) {
				throw new XPathException(new ErrorCode("exgit319", "Git API error on push"),
					"General API error pushing to '" + remote + "': " + e.toString());
			}
			
			
			Iterator<PushResult> pi = p.iterator();
			while (pi.hasNext()) {
				PushResult o = pi.next();
				
				result.add(new StringValue(o.getRemoteUpdates().toString()));
				result.add(new StringValue(o.getMessages().toString()));
				
				// TODO ggf. getRemoteUpdates().iterator().next() -> einzelne teile des Status
			}
			break;
		}
		case "export":
		{
			// We check that an export only happens into a repo or empty directory
			String local = getDir(args[0].toString()).toString();
			String collection = args[1].toString();
			
			result.add(new BooleanValue(writeCollectionToDisk(local, collection)));
			break;
		}
		case "exportFiles":
		{
			String local = getDir(args[0].toString()).toString();
			result.add(new BooleanValue(writeFilesToDisk(local, args[1].toString(), args[2])));
			break;
		}
		case "exportFiltered":
		{
			String local = getDir(args[0].toString()).toString();
			result.add(new BooleanValue(writeFilesFiltered(local, args[1].toString(), args[2].toString())));
			break;
		}
		case "pull":
		{
			String remote = args[1].toString();
			String username = args[2].toString();
			String password = args[3].toString();
			String local = getDir(args[0].toString()).toString();
			
			if (!isRepo(new File(local)))
				throw new XPathException(new ErrorCode("exgit030c", "Not a git repo"),
						"The selected path is not a git repo: " + local);
			
			PullResult p;
			try (Git git = getRepo(local)) {
				p = git.pull().setRemote(remote)
						.setCredentialsProvider(new UsernamePasswordCredentialsProvider(username, password))
						.call();
			} catch (InvalidRemoteException ire) {
				throw new XPathException(new ErrorCode("exgit323", "Invalid remote"),
					"Invalid remote when puling from " + remote + ": " + ire.getLocalizedMessage());
			} catch (TransportException te) {
				throw new XPathException(new ErrorCode("exgit324", "Transport Exception"),
					"Transport error when pulling from " + remote + te.getLocalizedMessage());
			} catch (GitAPIException e) {
				throw new XPathException(new ErrorCode("exgit329", "Git API error on pull"),
						"General API error pulling from remote '" + remote + "': " + e.toString());
			}
			
			result.add(new StringValue(p.toString()));
		}
			break;
		case "import":
			result.addAll(importCollectionFromDisk(args[0].toString(), args[1].toString()));
			break;
		case "tags":
		{
			String address = args[0].toString();
			Iterable<Ref> tags;
			
			if (address.startsWith("http") || address.startsWith("ssh")) {
				try {
					tags = Git.lsRemoteRepository().setRemote(address).setTags(true).call();
				} catch (GitAPIException e) {
					throw new XPathException(new ErrorCode("exgit409a", "Git API error fetching tags"),
						"General API fetching tags from '" + address + "': " + e.toString());
				}
			} else {
				if (!isRepo(new File(address)))
					throw new XPathException(new ErrorCode("exgit030b", "Not a git repo"),
							"The selected path is not a git repo: " + address);
				try (Git git = getRepo(address)) {
					git.fetch().setTagOpt(TagOpt.FETCH_TAGS).call();
					tags = git.tagList().call();
				} catch (GitAPIException e) {
					throw new XPathException(new ErrorCode("exgit409b", "Git API error fetching tags"),
						"General API fetching tags from '" + address + "': " + e.toString());
				}
			}
			
			MemTreeBuilder builder = context.getDocumentBuilder();
			builder.startDocument();
			builder.startElement(new QName("tags", null, null), null);
			
			for (Ref tag : tags) {
				String ref = tag.toString();
				if (ref.contains("tags")) {
					int start = ref.indexOf("[") + 1;
					int end = ref.indexOf('=');
					
					builder.startElement(new QName("tag", null, null), null);
					builder.addAttribute(new QName("name", null, null), ref.substring(start, end));
					builder.addAttribute(new QName("commit", null, null), ref.substring(end + 1, ref.length() - 1));
					builder.endElement();
				}
			}
			
			builder.endElement();
			builder.endDocument();
			
			return (NodeValue) builder.getDocument().getDocumentElement();
		}
		case "info":
		{
			String local = getDir(args[0].toString()).toString();
			if (!isRepo(new File(local)))
				throw new XPathException(new ErrorCode("exgit030a", "Not a git repo"),
						"The selected path is not a git repo: " + local);
			
			RevCommit commit;
			try (Git git = getRepo(local);
					Repository repository = git.getRepository();
					RevWalk walk = new RevWalk(repository);) {
				org.eclipse.jgit.lib.ObjectId commitId = repository.resolve(args[1].toString());
				commit = walk.parseCommit(commitId);
			} catch (Exception e) {
				throw new XPathException(new ErrorCode("exgit419", "Git API error getting repo info"),
						"General API getting repo info for " + local + ": " + e.toString());
			}
			
			Date commitDate = commit.getCommitterIdent().getWhen();
			String commitMsg = commit.getFullMessage();
			
			MemTreeBuilder builder = context.getDocumentBuilder();
			builder.startDocument();
			builder.startElement(new QName("commit", null, null), null);
			builder.addAttribute(new QName("id", null, null), args[1].toString());
			
			builder.startElement(new QName("message", null, null), null);
			builder.characters(commitMsg);
			builder.endElement();
			
			builder.startElement(new QName("date", null, null), null);
			builder.characters(ZonedDateTime.ofInstant(commitDate.toInstant(), ZoneId.of("UTC")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
			builder.endElement();
			
			builder.endElement();
			builder.endDocument();
			
			return (NodeValue) builder.getDocument().getDocumentElement();
		}
		case "memImport": {
			String remoteRepoURI = args[0].toString();
			String collection = args[1].toString();
			
			DfsRepositoryDescription repoDesc = new DfsRepositoryDescription();
			InMemoryRepository		 repo	  = new InMemoryRepository(repoDesc);
			Git						 git	  = new Git(repo);
			Logger 					 logger   = LogManager.getLogger();
			
			try {
				git.fetch()
				   .setRemote(remoteRepoURI)
				   .setRefSpecs(new RefSpec("+refs/heads/*:refs/heads/*"))
				   .call();
				repo.getObjectDatabase();
				
				ObjectId lastCommitId = repo.resolve("refs/heads/master");
				RevWalk  revWalk      = new RevWalk(repo);
				logger.info("Inspecting commit " + lastCommitId.toString() + " of repo " + remoteRepoURI);
				
				try {
					
					RevCommit commit   = revWalk.parseCommit(lastCommitId);
					RevTree   revTree  = commit.getTree();
					TreeWalk  treeWalk = new TreeWalk(repo);
					
					CloneCommand cc = Git.cloneRepository()
							.setURI(remoteRepoURI);
					cc.call();
					// TODO use --depth 2 as soon as it is implemented in jgit
					
					/* get previous commit; https://stackoverflow.com/a/40094665/1652861 */
					revWalk.markStart(commit);
					RevCommit previous = null;
					int count = 0;
					for (RevCommit rev : revWalk) {
						// got the previous commit.
						if (count == 1) {
							previous = rev;
						}
						count++;
					}
					
					/* Get diff between current and previous commit; https://www.codeaffine.com/2016/06/16/jgit-diff/ */
					AbstractTreeIterator oldTreeIterator = getCanonicalTreeParser(lastCommitId, git);
					AbstractTreeIterator newTreeIterator = getCanonicalTreeParser(previous, git);
					
					List<DiffEntry> diffEntries = git.diff()
							.setOldTree(oldTreeIterator)
							.setNewTree(newTreeIterator)
							.call();
					for (int i = 0; i < diffEntries.size(); i++) {
						// TODO evaluate OldPath, too, to see whether files were moved or deleted!
						String filename = diffEntries.get(i).getNewPath();
						result.add(new StringValue(filename));
						
						ObjectLoader loader = loadRemote(treeWalk, revTree, repo, filename);
						ObjectStream fileStream = loader.openStream();
						
						fileStream.close();
						
					}
				} finally {
					revWalk.close();
				}
			} catch (IOException e) {
				throw new XPathException(new ErrorCode("exgit322", "IO Error on memImport"),
					"IO error memImporting " + repo + " into " + collection + ": " + e.getLocalizedMessage());
			} catch (InvalidRemoteException e) {
				throw new XPathException(new ErrorCode("exgit323", "Invalid remote"),
						"Invalid remote when memImporting from " + remoteRepoURI + ": " + e.getLocalizedMessage());
			} catch (TransportException te) {
				throw new XPathException(new ErrorCode("exgit324", "Transport Exception"),
					"Transport error when pulling from " + remoteRepoURI + te.getLocalizedMessage());
			} catch (GitAPIException e) {
				throw new XPathException(new ErrorCode("exgit329", "Git API Error on memImport"),
					"General error cloning " + repo + " into " + collection + ": " + e.getLocalizedMessage());
			} finally {
				git.close();
			}
			
			break;
		}
		default:
			throw new XPathException(new ErrorCode("exgit100", "function not found"),
					"The requested function " + functionName + " was not found in this module");
		}
		
		return result;
	}
	
	/* get previous commit; https://stackoverflow.com/a/40094665/1652861 */
	private AbstractTreeIterator getCanonicalTreeParser(ObjectId commitId, Git git) throws IOException {
		try (RevWalk walk = new RevWalk(git.getRepository())) {
			RevCommit commit = walk.parseCommit(commitId);
			ObjectId treeId = commit.getTree().getId();
			try (ObjectReader reader = git.getRepository().newObjectReader()) {
				return new CanonicalTreeParser(null, reader, treeId);
			}
		}
	}
	
	// exception codes 5xx
	private ValueSequence importCollectionFromDisk (String pathToLocal, String pathToCollection) throws XPathException {
		Logger logger = LogManager.getLogger();
		
		Path repo = Paths.get(pathToLocal);
		logger.info("Got request to import from " + pathToLocal + " (parsed as " + repo.toString() + ") into " + pathToCollection);
		if (!Files.exists(repo))
			throw new XPathException(new ErrorCode("exgit500", "Path not found trying to ingest"),
					"Import could not find anything under " + pathToLocal + ".");
		
		Collection collection = getCollection(pathToCollection, LockMode.WRITE_LOCK);
		
		// we should now have a target to import into
		
		Iterator<Path> contents;
		try {
			contents = Files.list(repo).iterator();
		} catch (IOException e) {
			throw new XPathException(new ErrorCode("exgit522", "I/O error reading input"),
					"I/O error reading " + pathToLocal + ": " + e.getLocalizedMessage());
		}
		
		Txn transaction = null;
		ValueSequence result = null;
		try {
			transaction = BrokerPool.getInstance().getTransactionManager().beginTransaction();
			logger.info("Beginning import of " + repo.toString() + "; transaction " + transaction.getId());
			
			result = new ValueSequence();
			while (contents.hasNext()) {
				Path content = contents.next();
				String name = content.getFileName().toString();
				
				// skip hidden files (e.g. `.git`)
				if (name.startsWith(".")) continue;
				
				if (Files.isDirectory(content)) {
					result.addAll(importCollectionFromDisk(content.toString(), pathToCollection + "/" + name));
				} else {
					logger.info("Ingesting " + content.toString() + "; transaction " + transaction.getId());
					try (FileInputStream fis = new FileInputStream(content.toFile()))
					{
						storeFileToCollection(fis);
						
					} catch (FileNotFoundException fnf) {
						throw new XPathException(new ErrorCode("exgit550", "File not found ingesting"),
							"File not found trying to store " + content.toString() + " into " 
								+ collection.getURI().toString() + " : " + fnf.getLocalizedMessage());
					} catch (IOException e2) {
						throw new XPathException(new ErrorCode("exgit552", "I/O error ingesting"),
							"An I/O error occurred trying to store " + content.toString() + " into " 
								+ collection.getURI().toString() + " : " + e2.getLocalizedMessage());
					}
				}
			}
			
			transaction.commit();
			logger.info("Finished importing " + repo.toString() + "; transaction " + transaction.getId());
		} catch (EXistException e3) {
			throw new XPathException(new ErrorCode("exgit559", "Exist error creating a transaction"),
					"TAn error occurred creating a transaction for storing into " 
						+ collection.getURI().toString() + " : " + e3.getLocalizedMessage());
		} finally {
			if (transaction != null) {
				if (transaction.getState() != Txn.State.COMMITTED) {
					logger.warn("An error occurred importing " + repo.toString()
						+ "; aborting transaction " + transaction.getId()); 
					transaction.abort();
				}
				
				logger.info("closing transaction " + transaction.getId());
				transaction.close();
			}
		}
		
		collection.close();
		return result;
	}
	
	private StringValue storeFileToCollection (InputStream stream, String filename) {
		// Store XML and related file types
		if (filename.matches(".*(xml|xsl|xconf|html)$")) {
			BOMInputStream bis = new BOMInputStream(fis, false);
			String daten = IOUtils.toString(bis, "UTF-8");
			
			try {
				StringValue stored = storeXmlToCollection(collection, transaction, daten, filePath);
			}
			// TODO boolean: throw error when not valid or store as binary?
			
		} else {
			result.add(storeBinaryToCollection(collection, transaction, fis, content, name));
		}
		
	}
	
	private StringValue storeXmlToCollection (Collection collection, Txn transaction, String daten, XmldbURI filePath)
			throws XPathException, SAXException {
		IndexInfo info   = null;
		
		// Validate XML – returns IndexInfo necessary for storing
		try {
			info = collection.validateXMLResource(transaction, context.getBroker(), filePath, daten);
		} catch (PermissionDeniedException pde) {
			throw new XPathException(new ErrorCode("exgit121", "Permission denied"),
					"Permission denied creating a write lock validating " + filePath.toString() + " for collection " 
						+ collection.getURI().toString() + ": " + pde.getLocalizedMessage());
		} catch (Exception e) {
			throw new XPathException(new ErrorCode("exgit609", "General error validating XML file"),
				"A general error has occurred trying to validate" + filePath.toString()
					+ ": " + e.getLocalizedMessage());
		}
		
		// Store XML file to collection (info contains necessary information) 
		try {
			collection.store(transaction, context.getBroker(), info, daten);
		} catch (PermissionDeniedException pde) {
			throw new XPathException(new ErrorCode("exgit531", "Permission denied"),
				"Permission denied storing " + filePath.toString() + " into " 
					+ collection.getURI().toString() + ": " + pde.getLocalizedMessage());
		} catch (Exception e) {
			throw new XPathException(new ErrorCode("exgit539", "General error storing XML file"),
				"A genereal error has occurred trying to store" + filePath.toString() + " into "
					+ collection.getURI().toString() + ": " + e.getLocalizedMessage());
		}
		
		return new StringValue(filePath.toString() + " -> " + collection.getURI().toString());
	}
	
	private StringValue storeBinaryToCollection (Collection collection, Txn transaction, FileInputStream fis,
			Path content, String name) throws XPathException {
		BinaryDocument bin;
		
		try {
			bin = collection.validateBinaryResource(transaction, context.getBroker(), XmldbURI.create(name));
		} catch (SAXException | LockException e) {
			throw new XPathException(new ErrorCode("exgit543", "eXist error"),
					"A valdation or lock error on eXist's side occurred for file " + content.toString()
						+ ": " + e.getLocalizedMessage());
		} catch (PermissionDeniedException e) {
			throw new XPathException(new ErrorCode("exgit541a", "Permission denied validating binary file"),
					"The permission was denied while validating binary file " + content.toString()
						+ " : " + e.getLocalizedMessage());
		} catch (IOException ioe) {
			throw new XPathException(new ErrorCode("exgit542", "I/O error validating binary"),
					"An I/O error occurred while validating binary file " + content.toString()
						+ ": " + ioe.getLocalizedMessage());
		}
		
		String mime;
		if (name.endsWith(".css")) mime = "text/css";
		else if (name.endsWith(".js")) mime = "application/javascript";
		else if (name.endsWith(".xql") || name.endsWith(".xqm")) mime = "application/xquery";
		else mime = "application/octet-stream";
		
		try {
			collection.addBinaryResource(transaction,
					context.getBroker(),
					XmldbURI.create(name),
					fis,
					mime,
					bin.getContentLength());
		} catch (EXistException | SAXException | LockException | IOException e) {
			throw new XPathException(new ErrorCode("exgit549", "Error storing binary file"),
					"Error storing " + content.toString() + " into " + collection.getURI().toString()
						+ ": " + e.getLocalizedMessage());
		} catch (PermissionDeniedException e) {
			throw new XPathException(new ErrorCode("exgit541b", "Permission denied writing binary file to database"),
					"The permission was denied to write binary file " + content.toString() + " into " 
						+ collection.getURI().toString() + " : " + e.getLocalizedMessage());
		}
		
		return new StringValue(content.toString() + " stored as binary resource");
	}
	
	/* error codes 2xy */
	private boolean writeCollectionToDisk(String pathToLocal, String pathToCollection) throws XPathException {
		Path repo = getDir(pathToLocal);
		Collection collection = getCollection(pathToCollection, LockMode.READ_LOCK);
		
		Iterator<DocumentImpl> documents;
		Iterator<XmldbURI> subcollections;
		try {
			documents = collection.iterator(context.getBroker());
			subcollections = collection.collectionIterator(context.getBroker());
			collection.close();
		} catch (PermissionDeniedException e) {
			throw new XPathException(new ErrorCode("exgit201", "permission denied"),
					"Access to the requested contents of collection " + collection.getURI().toString()
					+ " was denied.");
		} catch (LockException e) {
			throw new XPathException(new ErrorCode("exgit202", "locking error"),
					"Failed to obtain a lock on " + collection.getURI().toString() + ".");
		}
		
		Logger logger = LogManager.getLogger();
		logger.info("Starting export of " + pathToCollection + " to " + pathToLocal);
		
		while (documents.hasNext()) {
			DocumentImpl doc = documents.next();
			
			Path filePath = Paths.get(repo.toString(), doc.getFileURI().toString());
			
			writeSingleFileToDisk(filePath, doc);
		}
		
		while (subcollections.hasNext()) {
			XmldbURI coll = subcollections.next();
			
			writeCollectionToDisk(repo.toString() + "/" + coll.toString(),
					pathToCollection + "/" + coll.toString());
		}
		
		collection.close();
		logger.info("Finished export of " + pathToCollection + " to " + pathToLocal);
		
		return true;
	}
	
	private boolean writeFilesToDisk (String pathToLocal, String pathToCollection, Sequence files) throws XPathException {
		Path repo;
		org.exist.collections.Collection collection;
		SequenceIterator paths;
		
		try {
			repo  = getDir(pathToLocal);
			paths = files.iterate();
			
			while (paths.hasNext()) {
				String file = paths.nextItem().getStringValue();
				Path filePath = Paths.get(repo.toString(), file);
				
				String filename = file.substring(file.lastIndexOf('/') + 1, file.length());
				String coll = pathToCollection + "/" + file.substring(0, file.lastIndexOf('/') + 1);
				
				Logger logger = LogManager.getLogger();
				logger.info("exporting " + filename + " from " + coll + " to " + filePath.toString());
				
				collection = getCollection(coll, LockMode.READ_LOCK);
				LockedDocument doc;
				try {
					doc = collection.getDocumentWithLock(context.getBroker(), XmldbURI.create(filename), LockMode.READ_LOCK);
					writeSingleFileToDisk(filePath, doc.getDocument());
					doc.close();
					collection.close();
				} catch (PermissionDeniedException e) {
					throw new XPathException(new ErrorCode("exgit211", "Permission denied"),
							"Permission denied reading " + file + " from " + pathToCollection + ".");
				} catch (LockException le) {
					throw new XPathException(new ErrorCode("exgit214", "Permission denied"),
							"Error getting a lock on " + file + " from " + pathToCollection + ".");
				}
			}
		} catch (XPathException xpe) {
			throw new XPathException(new ErrorCode("exgit202", "error"),
					xpe.toString() + ".");
		}
		
		return true;
	}
	
	private boolean writeFilesFiltered (String pathToLocal, String pathToCollection, String regex) throws XPathException {
		Path repo = getDir(pathToLocal);
		org.exist.collections.Collection collection = getCollection(pathToCollection, LockMode.READ_LOCK);
		
		Iterator<DocumentImpl> documents;
		Iterator<XmldbURI> subcollections;
		try {
			documents = collection.iterator(context.getBroker());
			subcollections = collection.collectionIterator(context.getBroker());
			//collection.getLock().acquire(LockMode.READ_LOCK);
		} catch (PermissionDeniedException e) {
			throw new XPathException(new ErrorCode("exgit221", "permission denied"),
					"Access to the requested contents of collection " + collection.getURI().toString()
					+ " was denied.");
		} catch (LockException e) {
			throw new XPathException(new ErrorCode("exgit222", "locking error"),
					"Failed to obtain a lock on " + collection.getURI().toString() + ".");
		}
		
		Logger logger = LogManager.getLogger();
		logger.info("Starting export of " + pathToCollection + " to " + pathToLocal + " with filter " + regex);
		
		while (documents.hasNext()) {
			DocumentImpl doc = documents.next();
			
			Path filePath = Paths.get(repo.toString(), doc.getFileURI().toString());
			
			if (filePath.toString().matches(regex)) {
				writeSingleFileToDisk(filePath, doc);
			}
		}
		
		while (subcollections.hasNext()) {
			XmldbURI coll = subcollections.next();
			
			writeFilesFiltered(repo.toString() + "/" + coll.toString(),
					pathToCollection + "/" + coll.toString(), regex);
		}
		
		collection.close();
//		logger.info(collection.getLock().toString());
//		collection.release(LockMode.READ_LOCK);
		logger.info("Finished export of " + pathToCollection + " to " + pathToLocal);
		
		return true;
	}
	
	private void writeSingleFileToDisk (Path filePath, DocumentImpl doc) throws XPathException {
		Serializer serializer = context.getBroker().getSerializer();
		Writer writer;
		
		Logger logger = LogManager.getLogger();
		logger.info("writing " + doc.getDocumentURI() + " (" + doc.getDocId() + ") to " + filePath.toString());
		
		try {
			writer = new OutputStreamWriter(Files.newOutputStream(filePath), "UTF-8");
		} catch (UnsupportedEncodingException e) {
			throw new XPathException(new ErrorCode("exgit291", "unknown encoding"),
					"The JRE does not know UTF-8; sth. very strange is going on.");
		} catch (IOException e) {
			throw new XPathException(new ErrorCode("exgit292", "I/O error"),
					"Cannot write to " + filePath.toString() + ".");
		}
		
		try {
			serializer.reset();
			serializer.serialize(doc, writer);
			
		} catch (SAXException e) {
			throw new XPathException(new ErrorCode("exgit293", "serializer exception"),
					"Serializing " + filePath.toString() + " failed: " + e.getLocalizedMessage() + ".");
		}
		finally {
			try {
				writer.close();
			} catch (Exception e) {
				throw new XPathException(new ErrorCode("exgit294", "serializer exception"),
						"Cannot close " + filePath.toString() + ": " + e.getLocalizedMessage() + ".");
			}
		}
	}
	
	/* exception codes 00x */
	private Git getRepo (String dirpath) throws XPathException {
		FileRepositoryBuilder builder = new FileRepositoryBuilder();
		Repository repository;
		File repo = new File(dirpath);
		
		FileRepositoryBuilder t = builder.findGitDir(repo).readEnvironment();
		
		try {
			repository = t.build();
		} catch (IOException e) {
			throw new XPathException(new ErrorCode("exgit001", "I/O Error on repo"),
					"I/O error when trying to read " + dirpath);
		} catch (Exception e) {
			throw new XPathException(new ErrorCode("exgit000", "not a repo"), 
					"The directory at " + dirpath + " is not a git repo.");
		}
		
		return new Git(repository);
	}
	
	/* exception codes 11x */
	private Collection getCollection(String pathToCollection, LockMode mode) throws XPathException {
		Collection collection;
		XmldbURI collectionURI = XmldbURI.create(pathToCollection);
		
		// Make sure a collection exists and is readable
		try {
			collection = context.getBroker().getCollection(collectionURI);
		} catch (PermissionDeniedException pde) {
			throw new XPathException(new ErrorCode("exgit101", "Permission denied reading collection"),
				"Permission denied when trying to read from the existing collection " + pathToCollection
						+ ": " + pde.toString());
		}
		
		// when trying to write, create the collection if necessary
		if (collection == null && mode == LockMode.WRITE_LOCK) {
			try (Txn collTransaction = BrokerPool.getInstance().getTransactionManager().beginTransaction()) {
				collection = context.getBroker()
									.getOrCreateCollection(collTransaction, collectionURI);
				context.getBroker().saveCollection(collTransaction, collection);
				collTransaction.commit();
			} catch (PermissionDeniedException pde) {
				throw new XPathException(new ErrorCode("exgit111", "Permission denied creating collection"),
						"Permission denied when trying to crate the collection " + pathToCollection
								+ ": " + pde.toString());
			} catch (Exception e) {
				throw new XPathException(new ErrorCode("exgit119", "General error creating collection"),
						"A genreal API error occurred when trying to crate the collection "
								+ pathToCollection + ": " + e.toString());
			}
		}
		collection.close();
		
		// open collection
		try {
			collection = context.getBroker().openCollection(collectionURI, mode);
		} catch (PermissionDeniedException pde) {
			String errorCode = mode == LockMode.WRITE_LOCK ? "exgit121" : "exgit 101";
			throw new XPathException(new ErrorCode(errorCode, "Permission denied accessing collection"),
					"Permission denied when trying to access the existing collection " + pathToCollection
							+ ": " + pde.toString());
		}
		
		if (collection == null) {
			throw new XPathException(new ErrorCode("exgit100", "collection not found"),
					"The requested collection " + pathToCollection + " was not found.");
		}
		
		return collection;
	}
	
	/* exception codes 0xx */
	/**
	 * checks whether pathToLocal is an empty directory or a repo; if it does not exist within its parent,
	 * it will be created.
	 * @param pathToLocal – must be an absolute path
	 * @return Path
	 * @throws XPathException
	 */
	private Path getDir (String pathToLocal) throws XPathException {
		Path repo = Paths.get(pathToLocal);
		
		if (!repo.isAbsolute())
			throw new XPathException(new ErrorCode("exgit003", "not an absolute path"),
				"The path supplied is not an absolute path: " + pathToLocal + ".");
		
		/* if the parent does not exist, we do not attempt to create a hierarchy */
		if (!Files.exists(repo.getParent()))
			throw new XPathException(new ErrorCode("exgit000", "no such file or directory"),
				"Nothing was found under the given (parent) path: "
					+ repo.getParent().toAbsolutePath().toString() + ".");
		
		if (!Files.exists(repo)) {
			try {
				Files.createDirectory(repo);
			} catch (IOException e) {
				throw new XPathException(new ErrorCode("exgit022", "I/O error: cannot create directory"),
					"An I/O error occurred trying to create " + pathToLocal + ".");
			}
		} else {
			File possibleGitRepo = new File(pathToLocal);
			
			if (!possibleGitRepo.isDirectory())
				throw new XPathException(new ErrorCode("exgit010", "no such directory"),
					"The given path points to a file: " + pathToLocal + ".");
			if (!Files.isWritable(repo))
				throw new XPathException(new ErrorCode("exgit011", "not writable"),
					"The permission to write was denied for " + pathToLocal + ".");
		}
		
		return repo;
	}
	
	private boolean isRepo(File possibleGitRepo) throws XPathException {
		/* empty directory: can be used to clone into */
		/*if (possibleGitRepo.list().length == 0) return true;*/
		
		/* strangely, this may complain even immediately after a fresh clone */
		return true;
				//RepositoryCache.FileKey.isGitRepository(possibleGitRepo, FS.DETECTED);
		
		/*FileRepositoryBuilder trb = new FileRepositoryBuilder();
		trb.setMustExist(true).setGitDir(possibleGitRepo);
		try {
			trb.build();
		} catch (RepositoryNotFoundException rnfe) {
			throw new XPathException(new ErrorCode("exgit030", "not a repository"),
				"The given path was found, not empty and is not a repo: "
					+ possibleGitRepo.getAbsolutePath() + ".");
		} catch (IOException ioe) {
			throw new XPathException(new ErrorCode("exgit032", "I/O error checking for repo"),
				"An I/O error occurred trying to check " + possibleGitRepo.getAbsolutePath() + ".");
		}
		return true;*/
	}
	
	/* In-memory only solution to get files from a remote repo; https://stackoverflow.com/a/54486558/1652861 */
	private ObjectLoader loadRemote(TreeWalk treeWalk, RevTree revTree, InMemoryRepository repo, String filename) {
		try {
			treeWalk.addTree(revTree);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		treeWalk.setRecursive(true);
		treeWalk.setFilter(PathFilter.create(filename));
		
		try {
			if (!treeWalk.next()) {
				return null;
			}
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		
		ObjectId objectId = treeWalk.getObjectId(0);
		ObjectLoader loader;
		try {
			loader = repo.open(objectId);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return loader;
	}
	
	private List<String> lastCommitFiles (TreeWalk treeWalk, RevTree revTree, InMemoryRepository repo) throws XPathException {
		final List<String> paths = new ArrayList<>();
		
		try {
			treeWalk.addTree(revTree);
			treeWalk.setRecursive(true);
			treeWalk.setPostOrderTraversal(true);
			
			while (treeWalk.next()) {
				paths.add(treeWalk.getPathString());
			}
			
			return paths;
		} catch (IOException e) {
			throw new XPathException(new ErrorCode("exgit900", "IOError for remote repo"),
					"IO error accessing remote for in Memory repo");
		}
	}
}
