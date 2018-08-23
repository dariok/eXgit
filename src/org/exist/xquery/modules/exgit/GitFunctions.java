package org.exist.xquery.modules.exgit;

import java.io.File;
import java.io.FileInputStream;
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
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.BOMInputStream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.exist.EXistException;
import org.exist.collections.Collection;
import org.exist.collections.IndexInfo;
import org.exist.collections.triggers.TriggerException;
import org.exist.dom.QName;
import org.exist.dom.memtree.MemTreeBuilder;
import org.exist.dom.persistent.BinaryDocument;
import org.exist.dom.persistent.DocumentImpl;
import org.exist.security.PermissionDeniedException;
import org.exist.storage.BrokerPool;
import org.exist.storage.lock.Lock.LockMode;
import org.exist.storage.serializers.Serializer;
import org.exist.storage.txn.TransactionException;
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
							"Structured information."))
			};

	public GitFunctions(XQueryContext context, FunctionSignature signature) {
		super(context, signature);
	}

	public Sequence eval(Sequence[] args, Sequence contextSequence) throws XPathException {
		String functionName = getSignature().getName().getLocalPart();
		ValueSequence result = new ValueSequence();
		MemTreeBuilder builder = context.getDocumentBuilder();
		
		/* TODO somehow store within the collection that it belongs to a git repo and which repo that is
		 * and where that repo  is to be found on the file system
		 */
		// TODO load configuration from a file in the database
		// Should this be a configuration file in /db/system or should it be stored in the collection?
		
		// TODO function to initialize the git repo
		
		Git git = null;
		switch (functionName) {
		case "checkout":
			try {
				git = getRepo(args[0].toString());
				git.checkout().setName(args[1].toString()).call();
			} catch (GitAPIException e) {
				throw new XPathException(new ErrorCode("exgit361", "Git API Error on checkout"),
						"Error cloning: " + e.getLocalizedMessage());
			} finally {
				git.close();
			}
			
			break;
		case "clone":
			String repo = args[0].toString();
			String local = args[1].toString();
			
			try {
				if (args.length == 2) {
					git = Git.cloneRepository()
							.setURI(repo)
							.setDirectory(new File(local))
							.setCloneSubmodules(true)
							.call();
				} else {
					git = Git.cloneRepository()
							.setURI(repo)
							.setDirectory(new File(local))
							.setBranchesToClone(Collections.singleton(args[2].toString()))
							.setBranch(args[2].toString())
							.setCloneSubmodules(true)
							.call();
				}
			} catch (GitAPIException e) {
				throw new XPathException(new ErrorCode("exgit351", "Git API Error on clone"),
						"Error cloning: " + e.getLocalizedMessage());
			} finally {
				git.close();
			}
			
			result.add(new StringValue("Cloned into: " + local));
			result.add(new StringValue("Result: " + git.toString()));
			
			break;
		case "commit":
			String message = args[1].toString();
			
			git = getRepo(args[0].toString());
			
			String add;	// added files
			String del; // deleted files
			String chg;	// changed files
			String mod;	// modified files
			RevCommit c;
			try {
				// make sure that new files are added; cf. #4
				@SuppressWarnings("unused")
				DirCache addC = git.add().addFilepattern(".").call();
				
				Status stat = git.status().call();
				chg = stat.getChanged().toString();
				mod = stat.getModified().toString();
				add = stat.getAdded().toString();
				del = stat.getRemoved().toString();
				
				if (args.length == 2) {
					c = git.commit().setAll(true).setMessage(message).call();
				} else {
					c = git.commit().setAll(true).setMessage(message).setAuthor(args[2].toString(), args[3].toString()).call();
				}
			} catch (GitAPIException e) {
				throw new XPathException(new ErrorCode("exgit301", "Git API Error on commit"),
						"Error committing: " + e.getLocalizedMessage());
			} finally {
				git.close();
			}
			
			result.add(new StringValue("Path to local repo: " + args[0].toString()));
			result.add(new StringValue("Changed: " + chg));
			result.add(new StringValue("Modified: " + mod));
			result.add(new StringValue("Added: " + add));
			result.add(new StringValue("removed: " + del));
			result.add(new StringValue("Revision: " + c.getId().toString()));
			result.add(new StringValue("Author: " + c.getAuthorIdent().toString()));
			
			break;
		case "push":
		{
			String remote = args[1].toString();
			String username = args[2].toString();
			String password = args[3].toString();
			
			git = getRepo(args[0].toString());
			
			Iterable<PushResult> p;
			try {
				p = git.push().setRemote(remote)
						.setCredentialsProvider(new UsernamePasswordCredentialsProvider(username, password)).call();
			} catch (GitAPIException e) {
				throw new XPathException(new ErrorCode("exgit311", "Git API error on push"),
						"Error pushing to remote '" + remote + "': " + e.toString());
			} finally {
				git.close();
			}
			
			
			Iterator<PushResult> pi = p.iterator();
			while (pi.hasNext()) {
				PushResult o = pi.next();
				
				result.add(new StringValue(o.getRemoteUpdates().toString()));
				result.add(new StringValue(o.getMessages().toString()));
				
				// TODO ggf. getRemoteUpdates().iterator().next() -> einzelne teile des Status
			}
		}
			break;
		case "export":
			result.add(new BooleanValue(writeCollectionToDisk(args[0].toString(), args[1].toString())));
			break;
		case "pull":
		{
			String remote = args[1].toString();
			String username = args[2].toString();
			String password = args[3].toString();
			
			git = getRepo(args[0].toString());
			
			PullResult p;
			try {
				p = git.pull().setRemote(remote).setCredentialsProvider(new UsernamePasswordCredentialsProvider(username, password)).call();
			} catch (GitAPIException e) {
				throw new XPathException(new ErrorCode("exgit401", "pull error"),
						"One of several possible errors has occurred pulling " + args[0].toString() + ": "
							+ e.getLocalizedMessage());
			} finally {
				git.close();
			}
			
			result.add(new StringValue(p.toString()));
		}
			break;
		case "import":
			result.addAll(readCollectionFromDisk(args[0].toString(), args[1].toString()));
			break;
		case "tags":
			String address = args[0].toString();
			
			Iterable<Ref> tags;
			try {
				if (address.startsWith("http") || address.startsWith("ssh")) {
					tags = Git.lsRemoteRepository().setRemote(address).setTags(true).call();
				} else {
					git = getRepo(address);
					git.fetch().setTagOpt(TagOpt.FETCH_TAGS).call();
					tags = git.tagList().call();
					git.close();
				}
			} catch (GitAPIException e) {
				throw new XPathException(new ErrorCode("exgit410", "repository error"),
						"One of several possible errors has occurred trying to retrieve all tags from " + args[0].toString() + ": "
							+ e.getLocalizedMessage());
			}
			
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
		case "info":
			git = getRepo(args[0].toString());
			Repository repository = git.getRepository();
			RevWalk walk = new RevWalk(repository);
			RevCommit commit;
			
			try {
				org.eclipse.jgit.lib.ObjectId commitId = repository.resolve(args[1].toString());
				commit = walk.parseCommit(commitId);
			} catch (Exception e) {
				throw new XPathException(new ErrorCode("exgit420", "repository error"),
						"One of several possible errors has occurred trying to retrieve meta data for commit " + args[1].toString() + ": "
							+ e.getLocalizedMessage());
			}
			
			Date commitDate = commit.getCommitterIdent().getWhen();
			String commitMsg = commit.getFullMessage();
			
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
		default:
			throw new XPathException(new ErrorCode("exgit000", "function not found"),
					"The requested function " + functionName + " was not found in this module");
		}
		
		return result;
	}
	
	// exception codes 5xx
	private ValueSequence readCollectionFromDisk (String pathToLocal, String pathToCollection) throws XPathException {
		Path repo = getDir(pathToLocal);
		
		//org.exist.collections.Collection collection = getCollection(pathToCollection);
		Txn collTransaction;
		XmldbURI uri = XmldbURI.create(pathToCollection);
		Collection collection;
		try {
			collection = context.getBroker().getCollection(uri);
			if (collection == null) {
				collTransaction = BrokerPool.getInstance().getTransactionManager().beginTransaction();
				collection = context.getBroker().getOrCreateCollection(collTransaction,
						XmldbURI.create(pathToCollection));
				context.getBroker().saveCollection(collTransaction, collection);
				collTransaction.commit();
				collTransaction.close();
				collection.release(LockMode.WRITE_LOCK);
			}
		} catch (EXistException | TriggerException | PermissionDeniedException | IOException e1) {
			throw new XPathException(new ErrorCode("exgit500", "collection not found"),
					"Could not find nor create " + pathToCollection + ": " + e1.getLocalizedMessage());
		}
		
		
		ValueSequence result = new ValueSequence();
		
		Iterator<Path> contents;
		try {
			contents = Files.list(repo).iterator();
		} catch (IOException e) {
			throw new XPathException(new ErrorCode("exgit501", "I/O error"),
					"I/O error reading " + pathToLocal + ": " + e.getLocalizedMessage());
		}
			
		while (contents.hasNext()) {
			Path content = contents.next();
			
			String name;
			if (content.toString().contains("/")) {
				name = content.toString().substring(content.toString().lastIndexOf('/') + 1);
			} else {
				name = content.toString().substring(content.toString().lastIndexOf('\\') + 1);
			}
			
			if (name.startsWith(".")) continue;
			
			if (Files.isDirectory(content) ) {
				result.addAll(readCollectionFromDisk(content.toString(), pathToCollection + "/" + name));
			} else {
				// TODO ggf. filter übergeben lassen?
				if (content.toString().matches(".*(xml|xsl|css|xql|xqm|js|xconf|html)$")) {
					Txn transaction;
					try {
						transaction = BrokerPool.getInstance().getTransactionManager().beginTransaction();
					} catch (EXistException e) {
						throw new XPathException(new ErrorCode("exgit511", "Transaction error"),
								"Error creating transaction to store in " + pathToCollection + ": "
										+ e.getLocalizedMessage());
					}
					
					FileInputStream fis;
					BOMInputStream bis;
					String daten;
					try {
						fis = new FileInputStream(content.toFile());
						bis = new BOMInputStream(fis, false);
						daten = IOUtils.toString(bis, "UTF-8");
					} catch (IOException e) {
						throw new XPathException(new ErrorCode("exgit521", "I/O error"),
								"I/O error reading " + content.toString() + ": " + e.getLocalizedMessage());
					} finally {
						transaction.abort();
						transaction.close();
						collection.getLock().release(LockMode.READ_LOCK);
					}
					
					try {
						if (name.matches(".*(xml|xsl|xconf|html)$") ) {
							IndexInfo info;
						
							try {
								info = collection.validateXMLResource(transaction, context.getBroker(),
										XmldbURI.create(name),
										daten.trim());
							} catch (EXistException | SAXException | LockException
									| IOException e) {
								throw new XPathException(new ErrorCode("exgit522a", "XML validation error"),
										"validation error for file " + content.toString() + ": " + e.getLocalizedMessage()
										+ " " + content.toString());
							} catch (PermissionDeniedException e) {
								throw new XPathException(new ErrorCode("exgit002", "Permission denied writing to database"),
										"The permission was denied to write " + content.toString() + ": " + e.getLocalizedMessage());
							}
							
							try {
								collection.store(transaction, context.getBroker(), info, 
										//data);
										daten);
							} catch (EXistException | SAXException | LockException e) {
								throw new XPathException(new ErrorCode("exgit523a", "store error"),
										"Error storing " + content.toString() + " into " + pathToCollection + ": "
												+ e.getLocalizedMessage());
							} catch (PermissionDeniedException e) {
								throw new XPathException(new ErrorCode("exgit002", "Permission denied writing to database"),
										"The permission was denied to write " + content.toString() + ": " + e.getLocalizedMessage());
							}
						} else {
							BinaryDocument bin;
							
							try {
								bin = collection.validateBinaryResource(transaction, context.getBroker(), XmldbURI.create(name));
							} catch (SAXException | LockException
									| IOException e) {
								throw new XPathException(new ErrorCode("exgit522b", "validation error"),
										"validation error for file " + content.toString() + ": " + e.getLocalizedMessage());
							} catch (PermissionDeniedException e) {
								throw new XPathException(new ErrorCode("exgit002", "Permission denied writing to database"),
										"The permission was denied to write " + content.toString() + ": " + e.getLocalizedMessage());
							}
							
							String mime;
							if (name.endsWith(".css")) mime = "text/css";
							else if (name.endsWith(".js")) mime = "application/javascript";
							else  mime = "application/xquery";
							
							try {
								collection.addBinaryResource(transaction, context.getBroker(), 
										XmldbURI.create(name),
										fis,
										mime,
										bin.getContentLength());
							} catch (EXistException | SAXException | LockException
									| IOException e) {
								throw new XPathException(new ErrorCode("exgit523b", "store error"),
										"Error storing " + content.toString() + " into " + pathToCollection + ": "
												+ e.getLocalizedMessage());
							} catch (PermissionDeniedException e) {
								throw new XPathException(new ErrorCode("exgit002", "Permission denied writing to database"),
										"The permission was denied to write " + content.toString() + ": " + e.getLocalizedMessage());
							}
						}
						
						result.add(new StringValue(content.toString() + " -> " + pathToCollection));
					} finally {
						try {
							transaction.commit();
							bis.close();
						} catch (TransactionException e) {
							throw new XPathException(new ErrorCode("exgit512", "transaction error"),
									"error committing transaction " + transaction.getId() + " for " + contents.toString()
									+ " into " + pathToCollection + ": " + e.getLocalizedMessage());
						} catch (IOException e) {
							throw new XPathException(new ErrorCode("exgit531", "I/O error closing stream"),
									"error closing stream for " + contents.toString() + ": " + e.getLocalizedMessage());
						} finally {
							transaction.abort();
							transaction.close();
							collection.getLock().release(LockMode.WRITE_LOCK);
							collection.getLock().release(LockMode.READ_LOCK);
						}
					}
				} else {
					result.add(new StringValue(content.toString() + " could not be handeled"));
				}
			}
			
		}
		return result;
	}
	
	/* error codes 2xy */
	private boolean writeCollectionToDisk(String pathToLocal, String pathToCollection) throws XPathException {
		Path repo = getDir(pathToLocal);
		org.exist.collections.Collection collection = getCollection(pathToCollection);
		
		Iterator<DocumentImpl> documents;
		Iterator<XmldbURI> subcollections;
		try {
			documents = collection.iterator(context.getBroker());
			subcollections = collection.collectionIterator(context.getBroker());
		} catch (PermissionDeniedException e) {
			throw new XPathException(new ErrorCode("exgit201", "permission denied"),
					"Access to the requested contents of collection " + collection.getURI().toString()
					+ " was denied.");
		} catch (LockException e) {
			throw new XPathException(new ErrorCode("exgit202", "locking error"),
					"Failed to obtain a lock on " + collection.getURI().toString() + ".");
		}
		
		while (documents.hasNext()) {
			DocumentImpl doc = documents.next();
			Serializer serializer = context.getBroker().getSerializer();
			
			Path filePath = Paths.get(repo.toString(), doc.getFileURI().toString());
			
			Writer writer;
			try {
				writer = new OutputStreamWriter(Files.newOutputStream(filePath), "UTF-8");
			} catch (UnsupportedEncodingException e) {
				throw new XPathException(new ErrorCode("exgit211", "unknown encoding"),
						"The JRE does not know UTF-8; sth. very strange is going on.");
			} catch (IOException e) {
				throw new XPathException(new ErrorCode("exgit212", "I/O error"),
						"Cannot write to " + filePath.toString() + ".");
			}
			
			try {
				serializer.reset();
				serializer.serialize(doc, writer);
				
			} catch (SAXException e) {
				throw new XPathException(new ErrorCode("exgit213", "serializer exception"),
						"Serializing " + filePath.toString() + " failed: " + e.getLocalizedMessage() + ".");
			}
			finally {
				try {
					writer.close();
				} catch (Exception e) {
					throw new XPathException(new ErrorCode("exgit214", "serializer exception"),
							"Cannot close " + filePath.toString() + ": " + e.getLocalizedMessage() + ".");
				}
			}
		}
		
		while (subcollections.hasNext()) {
			XmldbURI coll = subcollections.next();
			
			writeCollectionToDisk(repo.toString() + "/" + coll.toString(),
					pathToCollection + "/" + coll.toString());
		}
		
		collection.release(LockMode.READ_LOCK);
		
		return true;
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
	private org.exist.collections.Collection getCollection(String pathToCollection) throws XPathException {
		org.exist.collections.Collection collection;
		
		try {
			collection = context.getBroker().openCollection(XmldbURI.create(pathToCollection), LockMode.READ_LOCK);
		} catch (PermissionDeniedException e) {
			throw new XPathException(new ErrorCode("exgit111", "permission denied"),
					"Access to the requested collection " + pathToCollection + " was denied.");
		}
		
		if (collection == null) {
			throw new XPathException(new ErrorCode("exgit111", "collection not found"),
					"The requested collection " + pathToCollection + " was not found.");
		}
		
		return collection;
	}
	
	/* exception codes 10x */
	private Path getDir (String pathToLocal) throws XPathException {
		Path repo = Paths.get(pathToLocal);

		if (Files.exists(repo) && !Files.isDirectory(repo))
				throw new XPathException(new ErrorCode("exgit101", "no such directory"),
						"The requested local repository was not found under the given path "
								+ repo.toAbsolutePath().toString() + ".");
		if (Files.exists(repo) && !Files.isWritable(repo))
			throw new XPathException(new ErrorCode("exgit102", "not writable"),
					"The requested local repository " + repo.toAbsolutePath().toString() + " is not writable");
		
		if (!Files.exists(repo)) {
			try {
				Files.createDirectory(repo);
			} catch (IOException e) {
				throw new XPathException(new ErrorCode("exgit103", "cannot create directory"),
						"The requested local directory " + repo.toAbsolutePath().toString() + " could not be created");
			}
		}
		
		return repo;
	}
}
