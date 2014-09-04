/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package yasoco;

import java.util.*;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.search.similarities.LMJelinekMercerSimilarity;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.queries.mlt.MoreLikeThis;

/**
 *
 * @author dganguly
 */

class TermScore implements Comparable<TermScore> {
	String term;
	float score;

	TermScore(String term, float score) {
		this.term = term;
		this.score = score;
	}

	public int compareTo(TermScore that) {
		return score < that.score? 1: score == that.score? 0 : -1; // desc
	}

	public String toString() { return term + ":" + score; }
}

public class Yasoco {
    
    IndexSearcher searcher;
    IndexReader reader;
    Properties prop;
    int numWanted;
    Analyzer analyzer;
	float lambda;
	//MoreLikeThis mlt;

	static int maxlimit = 1024; 

    public Yasoco(String propFile) throws Exception {
        String index_dir = null;
        prop = new Properties();
        prop.load(new FileReader(propFile));
        index_dir = prop.getProperty("index");

        try {
            // get back the analyzer (now used to form the queries)
            // that was used by the indexer
            SCIndexer indexer = new SCIndexer(propFile);
            analyzer = indexer.getAnalyzer();

            File indexDir = new File(index_dir);
            reader = DirectoryReader.open(FSDirectory.open(indexDir));
            searcher = new IndexSearcher(reader);
			//mlt = new MoreLikeThis(reader);
			//mlt.setAnalyzer(analyzer);
			//mlt.setMaxQueryTerms(Integer.parseInt(prop.getProperty("num_q_terms", "10")));

            lambda = Float.parseFloat(prop.getProperty("lambda", "0.6"));
            searcher.setSimilarity(new LMJelinekMercerSimilarity(lambda));

            numWanted = Integer.parseInt(prop.getProperty("num_wanted", "1000"));
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }
    }

	// q: an o/p parameter
	List<TermScore> selTerms(int docId, String fieldName, Query q) throws Exception {
        
		int num_q_terms = Integer.parseInt(prop.getProperty("num_q_terms", "10"));
		int N = reader.numDocs();
		List<TermScore> tlist = new Vector<>();

		Terms terms = reader.getTermVector(docId, fieldName); //get terms vectors for one document and one field
		if (terms == null || terms.size() == 0)
			return tlist;

		TermsEnum termsEnum = terms.iterator(null); // access the terms for this field
		BytesRef term = null;

        int docLen = 0;
		while ((term = termsEnum.next()) != null) {// explore the terms for this field
			DocsEnum docsEnum = termsEnum.docs(null, null); // enumerate through documents, in this case only one
			int docIdEnum;

			while ((docIdEnum = docsEnum.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
				//get the term frequency in the document
				docLen += docsEnum.freq();
            }
        }
        
        termsEnum = terms.iterator(null); // access the terms for this field
		while ((term = termsEnum.next()) != null) {// explore the terms for this field
			Term t = new Term(fieldName, term);
			DocsEnum docsEnum = termsEnum.docs(null, null); // enumerate through documents, in this case only one
			int docIdEnum;

			while ((docIdEnum = docsEnum.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
				//get the term frequency in the document
				int tf = docsEnum.freq();
				float ntf = tf/(float)docLen;
				int df = (int)(reader.totalTermFreq(t));
				float idf = N/(float)df;
				float tf_idf = lambda*ntf + (1-lambda)*idf;

				tlist.add(new TermScore(term.utf8ToString(), tf_idf));
			}
		}
        
		Collections.sort(tlist); // desc
		List<TermScore> topList = tlist.subList(0, Math.min(tlist.size(), num_q_terms));
		return topList;
	}
   
	// Construct a short version of the query by selecting
	// only the top scoring terms	
    Query constructQuery(int docId) throws Exception {
		Query q = null;
		boolean formSelectiveQueries = Boolean.parseBoolean(prop.getProperty("toptermquery", "true"));
		/* MoreLikeThis not woking for some reason!
		if (formSelectiveQueries) {	
			q = mlt.like(docId);
			return q;
		}
		*/

		Document queryDoc = reader.document(docId);
        q = new BooleanQuery();
        int termCount = 0;
		TokenStream fs = null;
        
        List<IndexableField> fields = queryDoc.getFields();
        
        for (IndexableField field : fields) {
            String fieldName = field.name();
            if (fieldName.equals(JavaSCTree.FIELD_DOCNAME) ||
                fieldName.equals(JavaSCTree.FIELD_SC))
                continue;   // ignore non-searchable fields

			if (formSelectiveQueries) {
				List<TermScore> topList = selTerms(docId, field.name(), q);
				for (TermScore ts : topList) {
	        		Term thisTerm = new Term(field.name(), ts.term);
    	   			((BooleanQuery)q).add(new TermQuery(thisTerm), BooleanClause.Occur.SHOULD);
				}
			}
			else {
	            fs = queryDoc.getField(fieldName).tokenStream(analyzer);
    	        CharTermAttribute termAtt = fs.addAttribute(CharTermAttribute.class);
        	    fs.reset();

            	// print all tokens until stream is exhausted
          	  	while (fs.incrementToken()) {
            	    Term thisTerm = new Term(field.name(), termAtt.toString());
                	termCount++;
                	if (termCount == maxlimit) {
						maxlimit = maxlimit<<1;
						BooleanQuery.setMaxClauseCount(maxlimit);
                	}
                	((BooleanQuery)q).add(new TermQuery(thisTerm), BooleanClause.Occur.SHOULD);					
            	}
            	fs.end();
            	fs.close();            
			}
        }
        return q;
    }

    public String retrieve(int docId, Query query) throws Exception {
		Document queryDoc = reader.document(docId);
        TopScoreDocCollector collector = TopScoreDocCollector.create(numWanted, true);
        ScoreDoc[] hits = null;
        TopDocs topDocs = null;
		StringBuffer buff = new StringBuffer();
		String runName = prop.getProperty("runname");
        
        searcher.search(query, collector);
        topDocs = collector.topDocs();
        hits = topDocs.scoreDocs;

        for (int i = 0; i < hits.length; ++i) {
            int rdocId = hits[i].doc;
            Document d = searcher.doc(rdocId);

			String retDocName = d.get(JavaSCTree.FIELD_DOCNAME);
			String qDocName = queryDoc.get(JavaSCTree.FIELD_DOCNAME);
			if (qDocName.equals(retDocName))
				continue;

            buff.append(qDocName)
					.append("\tQ0\t")
                    .append(retDocName).append("\t")
				    .append((i+1)).append("\t")
                    .append(hits[i].score).append("\t")
					.append(runName).append("\n");
        }
		return buff.toString();
    }
    
    public void retrieveAll() throws Exception {
        
		String rfilename = prop.getProperty("resfile");
		FileWriter rfile = new FileWriter(rfilename);
        
        int maxDoc = reader.maxDoc();
        for (int i = 0; i < maxDoc; i++) {
			//System.out.println("Retrieving results for query: " + i);
            Query q = constructQuery(i);
            rfile.write(retrieve(i, q));
        }
        
        if (reader != null)
            reader.close();
		if (rfile != null)
			rfile.close();
    }
    

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        // TODO code application logic here
        String propFile = "init.properties";
        if (args.length > 0) {
            propFile = args[0];
        }

        try {
            Yasoco yasco = new Yasoco(propFile);
            yasco.retrieveAll();
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }
    }
    
}
