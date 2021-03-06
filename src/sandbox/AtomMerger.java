package sandbox;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.script.Bindings;


import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.w3c.dom.Attr;
import org.w3c.dom.CDATASection;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.Text;
import org.w3c.tidy.Tidy;


public class AtomMerger extends AbstractApplication
	{
	private static final String ATOM="http://www.w3.org/2005/Atom";
	private static final String DATE_FORMATS[]={
		"yyyy-MM-dd'T'HH:mm:ssXXX",
		"EEE, dd MMM yyyy HH:mm:ss"
		};
	public static class EntryBean
		{
		private final Node root;
		EntryBean(final Node root)
			{
			this.root = root;
			}
		
		private String _get(final String localName) {
			String t="";
			for(Node c2= root.getFirstChild();c2!=null;c2=c2.getNextSibling())
					{
					if(c2.getNodeType()!=Node.ELEMENT_NODE) continue;
					if(!c2.getLocalName().equals(localName)) continue;
					t  = c2.getTextContent().trim();
					break;
					}
			return t;
			}
		
		public String getId() {
			return _get("id");
			}
		public Date getDate() {
			for(Node c2= root.getFirstChild();c2!=null;c2=c2.getNextSibling())
					{
					if(c2.getNodeType()!=Node.ELEMENT_NODE) continue;
					if(!c2.getLocalName().equals("updated")) continue;
					String u  = c2.getTextContent().trim();
					
					for (final String format : DATE_FORMATS) {
					final SimpleDateFormat fmt = new SimpleDateFormat(format);
					fmt.setLenient(true);
					try { return fmt.parse(u); }
					catch (Exception err) { }
					}
					}
			return new Date();
			}
		public String getSummary() {
			return _get("summary");
			}
		
		public String getTitle() {
			return _get("title");
			}
		public String getUrl() {
			String url="";
			for(Node c2= root.getFirstChild();c2!=null;c2=c2.getNextSibling())
					{
					if(c2.getNodeType()!=Node.ELEMENT_NODE) continue;
					if(!c2.getLocalName().equals("link")) continue;
					final Element e2=Element.class.cast(c2);
					if(!e2.hasAttribute("href")) continue;
					url = e2.getAttribute("href");
					break;
					}
			return url;
			}	
		public String getContent() {
			return _get("content");
			}
		} 
	
	
	private static class EntrySorter implements Comparator<Node>
		{
		private Date defaultDate= new Date();
		EntrySorter() {
			
			}
		private Date getDate(Node o1) {
			String updated=null;

			for (Node c = o1.getFirstChild(); c != null; c = c.getNextSibling()) {
				if (c.getNodeType() == Node.ELEMENT_NODE && c.getLocalName().equals("updated")) {
					updated = c.getTextContent().replaceAll(" PST","");
					break;
				}
				}
				
			if(updated==null) return defaultDate;
			
			for (final String format : DATE_FORMATS) {
				final SimpleDateFormat fmt = new SimpleDateFormat(format);
				fmt.setLenient(true);
				try {
					return fmt.parse(updated);
				}
				catch (Exception err) {
				}
			}
			LOG.info("bad date format : "+updated);
			return defaultDate;
			
		}
		@Override
		public int compare(Node o1, Node o2) {
			return getDate(o2).compareTo(getDate(o1));
			}
		}	
	
	private static class FileAndFilter
		{
		final File file;
		Transformer stylesheet=null;
		
		FileAndFilter(final String file)
		{
			this(new File(file));
		}
		
		FileAndFilter(final File file)
		{
			this.file=file;
		}
		Document transform(final Document src) throws Exception{
			if(this.stylesheet == null) {
				if(this.file == null) throw new NullPointerException("no file defined for fileandfilter.");
				final TransformerFactory factory = TransformerFactory.newInstance();
				// Use the factory to create a template containing the xsl file
				final Templates templates = factory.newTemplates(new StreamSource(this.file));
				this.stylesheet = templates.newTransformer();
				}
			final DOMResult domResult = new DOMResult();
			this.stylesheet.transform( new DOMSource(src), domResult);
			return (Document)domResult.getNode();
			}
		}
	
	private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger("atommerger");
	private FileAndFilter rss2atom=null;
	private FileAndFilter json2atom=null;
	private FileAndFilter html2atom=null;
	private FileAndFilter xml2atom=null;
	private final List<FileAndFilter> atomfilters=new ArrayList<>();
	private final List<FileAndFilter> rssfilters=new ArrayList<>();
	private int limitEntryCount=-1;
	
	private boolean ignoreErrors=false;
	private Document convertRssToAtom(Document rss) throws Exception
		{
		if(rss2atom==null) 
			throw new RuntimeException("XSLT stylesheet to convert rss to atom was not provided");
		Document atom  = this.rss2atom.transform(rss);
		if(!isAtom(atom))
			{
			throw new RuntimeException("Ouput is not atom");
			}
		return atom;
		}
	
	private boolean isAtom(final Document dom)
		{
		final Element root= dom.getDocumentElement();
		return root!=null && root.getLocalName().equals("feed") && root.getNamespaceURI().equals(ATOM);
		}
	private boolean isRss(final Document dom)
		{
		final Element root= dom.getDocumentElement();
		return root!=null && root.getLocalName().equals("rss");
		}

	private void cloneTidy(final Node root,final Node n)
		{
		final Document owner =root.getOwnerDocument();
		switch(n.getNodeType())
			{
			case Node.TEXT_NODE:
				{
				String text = Text.class.cast(n).getTextContent();
				if(text!=null) root.appendChild( owner.createTextNode(text));
				break ;
				}
			case Node.CDATA_SECTION_NODE:
				{
				String text = CDATASection.class.cast(n).getTextContent();
				if(text!=null) root.appendChild(owner.createTextNode(text));
				break;
				}
			case Node.ELEMENT_NODE:
				{
				final Element e= Element.class.cast(n);
				final NamedNodeMap atts = e.getAttributes();
				final Element r = owner.createElementNS(e.getNamespaceURI(),e.getNodeName());
				root.appendChild(r);
				for(int i=0;i< atts.getLength();++i)
				{
					Attr att=(Attr)atts.item(i);
					r.setAttributeNS("http://www.w3.org/1999/xhtml",att.getNodeName(),att.getValue());
					
				}
				
				for(Node c=e.getFirstChild();c!=null;c=c.getNextSibling())
					{
					this.cloneTidy(r,c); 
					}
				break;
				}
			default: LOG.warning(">>>>"+n.getNodeType()+ " "+n); break;
			}
		}

	
	@Override
	protected void fillOptions(Options options) {
		options.addOption(Option.builder("r2a").longOpt("rss2atom").hasArg(true).desc("Optional XSLT stylesheet transforming rss to atom.").build());
		options.addOption(Option.builder("x2a").longOpt("xml2atom").hasArg(true).desc("Optional XSLT stylesheet transforming xml to atom.").build());
		options.addOption(Option.builder("o").longOpt("output").hasArg(true).desc("File out.").build());
		options.addOption(Option.builder("j2a").longOpt("json2atom").hasArg(true).desc("Optional XSLT stylesheet transforming jsonx to atom.").build());
		options.addOption(Option.builder("h2a").longOpt("html2atom").hasArg(true).desc("Optional XSLT stylesheet transforming jsonx to atom.").build());
		options.addOption(Option.builder("i").longOpt("ignore").hasArg(false).desc("Ignore Errors").build());
		options.addOption(Option.builder("rf").longOpt("rssfilter").hasArgs().desc("Optional list of XSLT stylesheets filtering RSS (multiple)").build());
		options.addOption(Option.builder("af").longOpt("atomfilter").hasArgs().desc("Optional list of XSLT stylesheets filtering ATOM (multiple)").build());
		options.addOption(Option.builder("n").longOpt("maxcount").hasArgs().desc("Optional limit number of entries. default: no limit.").build());
		options.addOption(Option.builder("jse").longOpt("jsexpr").hasArgs().desc("optional javascript expression to filter entity 'entry'").build());
		options.addOption(Option.builder("jsf").longOpt("jsfile").hasArgs().desc("optional javascript file to filter entity 'entry'").build());
		super.fillOptions(options);
	}
	
	@Override
	protected int execute(CommandLine cmd) {
		File fileout=null;
		javax.script.CompiledScript compiledScript = null;
		Bindings bindings = null;
		
		if(cmd.hasOption("o")) {
			fileout= new File( cmd.getOptionValue("o"));
		}
		
		if(cmd.hasOption("r2a")) {
			this.rss2atom= new FileAndFilter( cmd.getOptionValue("r2a"));
		}
		if(cmd.hasOption("j2a")) {
			this.json2atom= new FileAndFilter( cmd.getOptionValue("j2a"));
		}

		if(cmd.hasOption("h2a")) {
			this.html2atom= new FileAndFilter( cmd.getOptionValue("h2a"));
		}
		
		
		if(cmd.hasOption("x2a")) {
			this.xml2atom= new FileAndFilter( cmd.getOptionValue("x2a"));
		}
		if(cmd.hasOption("i")) {
			this.ignoreErrors = true;
		}
		
		if(cmd.hasOption("n")) {
			this.limitEntryCount = Integer.parseInt( cmd.getOptionValue("n"));
		}
		
		
		if(cmd.hasOption("jse") || cmd.hasOption("jsf"))
			{
			final javax.script.ScriptEngineManager manager = new javax.script.ScriptEngineManager();
			final javax.script.ScriptEngine engine = manager.getEngineByName("js");
			if(engine==null)
				{
				throw new RuntimeException("not available ScriptEngineManager: javascript. Use the SUN/Oracle JDK ?");
				}
			bindings = engine.createBindings();
			
			final javax.script.Compilable compilingEngine = (javax.script.Compilable)engine;
			try {
				if(cmd.hasOption("jse")) {
					compiledScript = compilingEngine.compile(cmd.getOptionValue("jse"));
					}
				else if(cmd.hasOption("jsf")) {
					java.io.FileReader r = null;
					try
						{
						r = new java.io.FileReader(cmd.getOptionValue("jsf"));
						compiledScript =  compilingEngine.compile(r);
						r.close();
						}
					catch(IOException err)
						{
						err.printStackTrace();
						return -1;
						}
					}
				}
			catch(Exception err)
				{
				err.printStackTrace();
				return -1;
				}
			}
		
		final List<String> args=cmd.getArgList();
		if(args.isEmpty())
			{
			System.err.println("Empty input. Double colon '--' missing ?");
			return -1;
			}
		try {
			if(cmd.hasOption("af")) {
				for(String f: IOUtils.expandList(cmd.getOptionValues("af"))) {
					this.atomfilters.add(new FileAndFilter(f));
				}
			}
			if(cmd.hasOption("rf")) {
				for(String f:IOUtils.expandList(cmd.getOptionValues("rf"))) {
					this.rssfilters.add(new FileAndFilter(f));
				}
			}

			final Set<String> paths = new LinkedHashSet<>();
			paths.addAll(IOUtils.expandList(args));
			paths.remove("");
		
			final Set<String> seenids = new HashSet<>();
			final DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
			dbf.setNamespaceAware(true);
			final DocumentBuilder db = dbf.newDocumentBuilder();
			Document outdom = db.newDocument();
			Element feedRoot = outdom.createElementNS(ATOM,"feed");
			outdom.appendChild(feedRoot);
			
			Element c = outdom.createElementNS(ATOM,"title");
			c.appendChild(outdom.createTextNode("AtomMerger"));
			feedRoot.appendChild(c);
			
			c = outdom.createElementNS(ATOM,"updated");
			c.appendChild(outdom.createTextNode(
					new SimpleDateFormat("E, dd MMM yyyy HH:mm:ss z").format(new Date())
					));
			feedRoot.appendChild(c);
			
			List<Node> chilrens = new ArrayList<>();
			
			for(String path:paths)
				{
				if(path.isEmpty()) continue;
				LOG.info(path);
				Document dom = null;
				
				try {
					//try as dom 
					try 
						{
						dom  = db.parse(path);
						}
					catch(Exception err)
						{
						dom=null;
						}
					
					if(dom==null && this.json2atom!=null) {
						final Json2Dom json2dom = new Json2Dom();
						InputStream in = IOUtils.openStream(path); 
						try {
						dom = json2dom.parse(in);
						dom = this.json2atom.transform(dom);
						}
						catch(Exception err) {
							dom=null;
						}
						finally {
							IOUtils.close(in);
						}
						}//json
					
					if(dom==null && this.html2atom!=null) {
						InputStream in = IOUtils.openStream(path); 
						try {
							final Tidy tidy = new Tidy();
							tidy.setXmlOut(true);
							tidy.setShowErrors(0);
							tidy.setShowWarnings(false);
							final Document newdoc =db.newDocument();
							cloneTidy(newdoc,tidy.parseDOM(in, null));
							dom = this.json2atom.transform(dom);
							}
						catch(final Exception err) {
							dom=null;
							err.printStackTrace();
							}
						finally {
							IOUtils.close(in);
							}
						}//html
					
					if(dom==null)
						{
						throw new IOException("Cannot parse "+path);
						}
					
					if(!(isRss(dom) || isAtom(dom)) && this.xml2atom!=null) {
						dom = this.xml2atom.transform(dom);
					}
					
					
					if(isRss(dom))
						{
						for(final FileAndFilter f:this.rssfilters)
							{
							dom = f.transform(dom);
							if(!isRss(dom)) {
								throw new RuntimeException(f.file.getPath()+" didn't convert to rss");
							}
							}
						dom = convertRssToAtom(dom);
						}
					else if(!isAtom(dom))
						{
						System.err.println("Not root atom or rss for "+path);
						return -1;
						}
					
					
					for(final FileAndFilter f:this.atomfilters)
						{
						dom = f.transform(dom);
						if(!isAtom(dom)) {
							throw new RuntimeException(f.file.getPath()+" didn't convert to atom");
							}
						}
					final Element root= dom.getDocumentElement();

					for(Node c1= root.getFirstChild();c1!=null;c1=c1.getNextSibling())
						{
						if(c1.getNodeType()!=Node.ELEMENT_NODE) continue;
						if(!c1.getLocalName().equals("entry")) continue;
						
						EntryBean bean=new EntryBean(c1);
						
						if(bean.getId()==null || seenids.contains(bean.getId())) continue;	
						
						if( compiledScript != null) {
							bindings.put("entry",bean);
							Object result = compiledScript.eval(bindings);
							if(result==null) continue;
							if(result instanceof Boolean)
								{
								if(Boolean.FALSE.equals(result)) continue;
								}
							else if(result instanceof Number)
								{
								if(((Number)result).intValue()!=1) continue;
								}
							else
								{
								continue;
								}
							}
						
						seenids.add(bean.getId());
						chilrens.add(outdom.importNode(c1, true) );
						if(this.limitEntryCount!=-1)
							{
							Collections.sort(chilrens,new EntrySorter());
							while(chilrens.size()>this.limitEntryCount) 
								{
								chilrens.remove(chilrens.size()-1);
								}
							}
						
						}
				} catch(Exception err)
					{
					if(this.ignoreErrors) {
						LOG.severe("Ignore error" + err);
						}
					else
						{
						throw err;
						}
					}
				}
			
			Collections.sort(chilrens,new EntrySorter());
			if(this.limitEntryCount!=-1)
				{
				while(chilrens.size()>this.limitEntryCount) 
					{
					chilrens.remove(chilrens.size()-1);
					}
				}
			
			for(Node n:chilrens)
				{
				feedRoot.appendChild(n);
				}
			
			final TransformerFactory trf = TransformerFactory.newInstance();
			final Transformer tr = trf.newTransformer();
			tr.setOutputProperty(OutputKeys.INDENT,"yes");
			tr.setOutputProperty(OutputKeys.ENCODING,"UTF-8");
			tr.transform(new DOMSource(outdom),
					fileout==null?
							new StreamResult(System.out):
							new StreamResult(fileout)
					);

			return 0;
		} catch (Exception e) {
			e.printStackTrace();
			return -1;
		} finally {
		}
		
		}
	


	
	public static void main(String[] args)
		{
		new AtomMerger().instanceMainWithExit(args);
		}
	
	
	}
