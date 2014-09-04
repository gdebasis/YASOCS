/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package yasoco;

/**
 *
 * @author dganguly
 */

import japa.parser.*;
import japa.parser.ast.*;
import japa.parser.ast.body.*;
import japa.parser.ast.expr.*;
import japa.parser.ast.stmt.*;
import japa.parser.ast.visitor.*;
import java.io.*;
import java.util.*;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.shingle.ShingleAnalyzerWrapper;
import org.apache.lucene.analysis.shingle.ShingleFilter;
import org.apache.lucene.analysis.shingle.ShingleFilterFactory;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.standard.UAX29URLEmailAnalyzer;
import org.apache.lucene.analysis.standard.UAX29URLEmailTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;


class ShingleAnalyzer extends Analyzer {

	Properties prop;

    ShingleAnalyzer(Properties prop) {
        super();
		this.prop = prop;
    }

    // No stopword removal... no stemming... no lowercasing
    // tokenizer followed by word n-grams formation
    @Override
    protected TokenStreamComponents createComponents(String fieldName, Reader reader) {
        TokenStream result = null;

        Tokenizer source = new UAX29URLEmailTokenizer(Version.LUCENE_46, reader);
        Map<String, String> shingleFilterParams = new HashMap<>();

		int minShingleSize = Integer.parseInt(prop.getProperty("minShingleSize", "2"));
		int maxShingleSize = Integer.parseInt(prop.getProperty("maxShingleSize", "3"));

		if (minShingleSize == 1 || maxShingleSize < minShingleSize) {
			// we don't want n-gram indexing
        	result = source;
		}
		else {
	        shingleFilterParams.put("minShingleSize", String.valueOf(minShingleSize));
    	    shingleFilterParams.put("maxShingleSize", String.valueOf(maxShingleSize));
        	shingleFilterParams.put("tokenSeparator", "#"); // looks good in luke
        	shingleFilterParams.put("outputUnigrams", "true");
        	shingleFilterParams.put("outputUnigramsIfNoShingles", "true");
            
        	result = new ShingleFilterFactory(shingleFilterParams).create(source);
		}

        return new TokenStreamComponents(source, result);
    }
}


class GenericVisitor<A> extends VoidVisitorAdapter<A> {
    StringBuffer buff;
    static final String Delim = " ";

    GenericVisitor() {
        buff = new StringBuffer();
    }

    @Override
    public String toString() {
        return buff.toString();
    }
}

class ClassVisitor<A> extends GenericVisitor<A> {

    @Override
    public void visit(ClassOrInterfaceDeclaration n, Object arg) {
        buff.append(n.getName());
    }
}

class MethodDeclarationVisitor<A> extends GenericVisitor<A> {
    
    @Override
    public void visit(MethodDeclaration n, Object arg) {
        buff.append(n.getName()).append(Delim);
        List<Parameter> plist = n.getParameters();
        if (plist != null) {
            for (Parameter p : plist)
                buff.append(p).append(Delim);
        }
        buff.append("\n");
    }
}

class StringLiteralVisitor<A> extends GenericVisitor<A> {
    StringBuffer arrayBuff = new StringBuffer();
    
    @Override
    public void visit(StringLiteralExpr n, Object arg) {
        buff.append(n.getValue()).append("\n");
    }

    public void visit(ArrayInitializerExpr n, Object arg) {
        if (n.getValues() != null) {
            for (Iterator<Expression> i = n.getValues().iterator(); i.hasNext();) {
                arrayBuff.append(i.next());
            }
        }
        arrayBuff.append("\n");
    }

    public void visit(ArrayAccessExpr n, Object arg) {
        arrayBuff.append(n.getName()).append(Delim).append(n.getIndex()).append("\n");
    }

    public void visit(ArrayCreationExpr n, Object arg) {
        arrayBuff.append(n.getType()).append(Delim);
        List<Expression> dims = n.getDimensions();
        if (dims != null) {
            for (Expression dim : dims)
                arrayBuff.append(dim.toString());
        }
        arrayBuff.append("\n");
    }
    
    String getArrayContent() { return arrayBuff.toString(); }
}

class MethodVisitor<A> extends GenericVisitor<A> {

    StringBuffer stmts;
    StringBuffer calls;

    public MethodVisitor() {
        stmts = new StringBuffer();
        calls = new StringBuffer();
    }
    
    @Override
    public void visit(BinaryExpr n, Object arg) {
        stmts.append(n.getLeft()).append(Delim).
                append(n.getOperator()).append(Delim).
                append(n.getRight());
        stmts.append("\n");
    }

    @Override
    public void visit(MethodCallExpr n, Object arg) {
        Expression scopeExp = n.getScope();
        if (scopeExp != null)
            calls.append(scopeExp.toString()).append(" ");
        
        calls.append(n.getName()).append(Delim);
        List<Expression> plist = n.getArgs();
        if (plist != null) {
            for (Expression p : plist)
                calls.append(p).append(Delim);
        }
        calls.append("\n");
    }
    
    String getCalls() { return calls.toString(); }
    String getStmts() { return stmts.toString(); }
}

// The abstract syntax tree of compiled java code
class JavaSCTree {
    File file;
    Document doc;
    Properties prop;
    
    static final String FIELD_DOCNAME = "name";  // doc name
    static final String FIELD_SC = "code";  // raw source code    
    static final String FIELD_CALLS = "calls";  // function calls 
    static final String FIELD_STRING_LITS = "strings";  // function calls 
    static final String FIELD_ARRAYS = "arrays";  // arrays
    static final String FIELD_FN_DEFS = "fdefs";    // method names
    static final String FIELD_CLASS_DEFS = "cdefs";    // class names
    static final String FIELD_STMTS = "stmts";    // statements
    static final String FIELD_COMMENTS = "comments"; // comments
    static final String FIELD_PACKAGE_IMPORTS = "imports"; // imports
    static final String FIELD_ALL = "content"; // merge all into one field with default analyzer
    
    public JavaSCTree(File file, Properties prop) {
        this.file = file;
        doc = new Document();
        this.prop = prop;
    }
    
    void buildTree() throws Exception {
        // creates an input stream for the file to be parsed
        FileInputStream in = new FileInputStream(file);
        CompilationUnit cu;
        try {
            // parse the file
            cu = JavaParser.parse(in);
        }
        finally {
            in.close();
        }
        
        doc.add(new Field(FIELD_SC, cu.toString(), Field.Store.YES, Field.Index.NOT_ANALYZED));
        
        GenericVisitor mv = new MethodVisitor();
        mv.visit(cu, null);
        
        boolean useFields = Boolean.parseBoolean(prop.getProperty("field", "true"));
        StringBuffer buff = new StringBuffer();

        if (useFields) {
            doc.add(new Field(FIELD_STMTS, ((MethodVisitor)mv).getStmts(), Field.Store.YES, Field.Index.ANALYZED, Field.TermVector.YES));
            doc.add(new Field(FIELD_CALLS, ((MethodVisitor)mv).getCalls(), Field.Store.YES, Field.Index.ANALYZED, Field.TermVector.YES));
        }
        else {
            buff.append(((MethodVisitor)mv).getStmts()).append("\n");
            buff.append(((MethodVisitor)mv).getCalls()).append("\n");
        }

        mv = new ClassVisitor();
        mv.visit(cu, null);
        if (useFields)
            doc.add(new Field(FIELD_CLASS_DEFS, mv.toString(), Field.Store.YES, Field.Index.ANALYZED, Field.TermVector.YES));
        else
            buff.append(mv.toString()).append("\n");

        mv = new StringLiteralVisitor();
        mv.visit(cu, null);
        
        if (useFields) {
            doc.add(new Field(FIELD_STRING_LITS, mv.toString(), Field.Store.YES, Field.Index.ANALYZED, Field.TermVector.YES));
            doc.add(new Field(FIELD_ARRAYS, ((StringLiteralVisitor)mv).getArrayContent(), Field.Store.YES, Field.Index.ANALYZED, Field.TermVector.YES));
        }
        else {
            buff.append(mv.toString()).append("\n");
            buff.append(((StringLiteralVisitor)mv).getArrayContent()).append("\n");
        }
        
        mv = new MethodDeclarationVisitor();
        mv.visit(cu, null);
        
        if (useFields)
            doc.add(new Field(FIELD_FN_DEFS, mv.toString(), Field.Store.YES, Field.Index.ANALYZED, Field.TermVector.YES));
        else
            buff.append(mv.toString()).append("\n");
        
        StringBuffer commentBuff = new StringBuffer();
        List<Comment> comments = cu.getComments();
        if (comments != null) {
            for (Comment comment : comments) {
                commentBuff.append(comment).append(" ");
            }
            commentBuff.append("\n");
        }
        if (useFields)
            doc.add(new Field(FIELD_COMMENTS, commentBuff.toString(), Field.Store.YES, Field.Index.ANALYZED, Field.TermVector.YES));
        else
            buff.append(commentBuff.toString()).append("\n");

        StringBuffer importBuff = new StringBuffer();
        List<ImportDeclaration> imports = cu.getImports();
        if (imports != null) {
            for (ImportDeclaration importdec : cu.getImports()) {
                importBuff.append(importdec.getName()).append(GenericVisitor.Delim);
            }
            importBuff.append("\n");
        }
        
        if (useFields)
            doc.add(new Field(FIELD_PACKAGE_IMPORTS, importBuff.toString(), Field.Store.YES, Field.Index.ANALYZED, Field.TermVector.YES));        
        else
            buff.append(importBuff.toString()).append("\n");
        
        if (!useFields) {
            doc.add(new Field(FIELD_ALL, buff.toString(), Field.Store.YES, Field.Index.ANALYZED, Field.TermVector.YES));                    
        }
    }
    
    Document getDoc() { return doc; }
    
}

public class SCIndexer {
    Analyzer analyzer;
    Properties prop;
    
    // The field structure would help us to formulate structured
    // search queries.
    
    public SCIndexer(String propFile) throws Exception {
        
        prop = new Properties();
        prop.load(new FileReader(propFile));

        Map<String, Analyzer> analyzerPerField = new HashMap<>();
        // standard stop-word removal/stemming for comments
        analyzerPerField.put(JavaSCTree.FIELD_COMMENTS, new StandardAnalyzer(Version.LUCENE_46));
        // white space tokenization for package names and string literals
        // no n-gram tokenization
        analyzerPerField.put(JavaSCTree.FIELD_PACKAGE_IMPORTS, new WhitespaceAnalyzer(Version.LUCENE_46));

        // default is the shingle analyzer... 
        analyzer = new PerFieldAnalyzerWrapper(new ShingleAnalyzer(prop), analyzerPerField);        
    }
    
    private void indexDirectory(IndexWriter writer, File dir)
        throws Exception {
        File[] files = dir.listFiles();
        Arrays.sort(files);
        for (int i=0; i < files.length; i++) {
            File f = files[i];
            if (f.isDirectory()) {
                //System.out.println("Indexing directory " + f.getName());
                indexDirectory(writer, f);  // recurse
            } else {
                indexFile(writer, f);
            }
        }
    }

    public Analyzer getAnalyzer() { return analyzer; }
    
    public void indexAll() {
        String index_dir, data_dir = null;
        data_dir = prop.getProperty("coll");
        index_dir = prop.getProperty("index");

        IndexWriter writer = null;
        try {
            File dataDir = new File(data_dir);
            File indexDir = new File(index_dir);

            IndexWriterConfig iwcfg = new IndexWriterConfig(Version.LUCENE_46, analyzer);
            iwcfg.setOpenMode(IndexWriterConfig.OpenMode.CREATE);

            writer = new IndexWriter(FSDirectory.open(indexDir), iwcfg);

            indexDirectory(writer, dataDir);

            writer.close();
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void indexFile(IndexWriter writer, File f) throws Exception {
        String name = f.getName();

        if (name.charAt(0) == '.')
            return;
        if (!name.endsWith(".java"))
            return;
 
        //System.out.println("Indexing java file " + name);
                
        // creates an input stream for the file to be parsed
        JavaSCTree stree = new JavaSCTree(f, prop);
        stree.buildTree();
        Document doc = stree.getDoc();

        doc.add(new Field(JavaSCTree.FIELD_DOCNAME, name, Field.Store.YES, Field.Index.NOT_ANALYZED));
        writer.addDocument(doc);
    }

    public static void main(String[] args) {
        String propFile = "init.properties";
        if (args.length > 0) {
            propFile = args[0];
        }

        try {
            SCIndexer indexer = new SCIndexer(propFile);
            indexer.indexAll();
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }
    }    
}
