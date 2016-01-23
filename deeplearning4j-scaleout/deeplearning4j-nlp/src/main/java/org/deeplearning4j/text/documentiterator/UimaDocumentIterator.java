package org.deeplearning4j.text.documentiterator;

import java.io.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.LineIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.uima.analysis_engine.AnalysisEngine;
import org.apache.uima.cas.CAS;
import org.apache.uima.jcas.tcas.Annotation;
import org.apache.uima.collection.CollectionReader;
import org.apache.uima.collection.CollectionReaderDescription;
import org.apache.uima.fit.factory.CollectionReaderFactory;
import org.apache.uima.fit.factory.AnalysisEngineFactory;
import org.apache.uima.fit.util.JCasUtil;
import org.apache.uima.resource.ResourceInitializationException;

//import org.cleartk.token.type.Sentence;
import org.cleartk.util.cr.FilesCollectionReader;

import org.deeplearning4j.text.annotator.TokenizerAnnotator;
import org.deeplearning4j.text.uima.UimaResource;



/**
 * Iterate over documents in the CAS
 * @author John Osborne 
 *
 */
public class UimaDocumentIterator implements DocumentIterator {

    private UimaResource resource;
    protected volatile Iterator<String> sentences;
    private static final Logger log  = LoggerFactory.getLogger(UimaDocumentIterator.class);
    CollectionReaderDescription readerDescription = null;
    CollectionReader reader = null;
    Object[] readerParams = null;
    Class sentenceClass;

    public UimaDocumentIterator(CollectionReaderDescription crd, Object[] params, UimaResource res, String sentence_classname) {
        readerDescription = crd;
        readerParams = params;
	resource = res;

        try {
            sentenceClass = Class.forName(sentence_classname);
            this.reader = CollectionReaderFactory.createReader(crd, params);
        } catch (ClassNotFoundException cnfe) {
            throw new RuntimeException(cnfe);
        } catch (ResourceInitializationException e) {
            throw new RuntimeException(e);
        }
    }


    @Override
    public synchronized  InputStream nextDocument() {
        try {
            if(sentences == null || !sentences.hasNext() && reader.hasNext()) {
                CAS cas =  resource.retrieve();
                try {
                    this.reader.getNext(cas);
                }catch(Exception e) {
                    log.warn("Done iterating returning an empty string");
                    return new BufferedInputStream(IOUtils.toInputStream(""));
                }
                resource.getAnalysisEngine().process(cas);
                List<String> list = new ArrayList<>();
                for(Object some_sentence : JCasUtil.select(cas.getJCas(), sentenceClass)) {
                    Annotation sentence = (Annotation) some_sentence;
                    list.add(sentence.getCoveredText());
                }
		if(list.size()==0) log.warn("Did not find any sentences annotated");
                sentences = list.iterator();
 		//Thread.sleep(1000);
                resource.release(cas);
            }   

            if(sentences.hasNext()) {
                String s = sentences.next();
		log.info("Got UIMA Sentence Annotation of:"+s);
                return new BufferedInputStream(IOUtils.toInputStream(s));
             }
        } catch (Exception e) {
           log.warn("Error reading input stream...this is just a warning..Going to return",e);
            return null;
        }
        return null;
    }


    @Override
    public synchronized boolean hasNext() {
        try {
            return reader.hasNext() || sentences != null && sentences.hasNext();
        } catch (Exception e) {
            throw new RuntimeException(e);
	}
    }

    @Override
    public void reset() {
        try {
            this.reader.close();
            this.reader = CollectionReaderFactory.createReader(readerDescription, readerParams);
            this.sentences=null;
        } catch (ResourceInitializationException rie) {
            throw new RuntimeException(rie);
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
	}
    }

}
