YASOCS
======

Yet Another Source Code Searcher: A tool to detect plagiarized java source files.

This is a very simple easy-to-use source code plagiarism detector. This software uses the javaparse light-weight Java source code compiler to get an annotated syntax tree. Selected elements from this parse tree, e.g. the class names, method call names, variable names etc. are then added to a Lucene index.

The program takes as input a properties file from where it reads the collection of source files to index. The retrieval module then treats every document as a query and retrieved top 5 most similar matching documents from the collection. Note that this software is only able to detect plagiarism at document level.
