package batch;

import edgeheap.EdgeHeapFile;
import global.AttrOperator;
import global.AttrType;
import global.EID;
import global.IndexType;
import global.NID;
import global.PageId;
import global.RID;
import global.TupleOrder;
import heap.FieldNumberOutOfBoundException;
import heap.HFBufMgrException;
import heap.HFDiskMgrException;
import heap.HFException;
import heap.Heapfile;
import heap.InvalidSlotNumberException;
import heap.InvalidTupleSizeException;
import heap.InvalidTypeException;
import heap.Tuple;

import index.EdgeIndexScan;
import index.IndexException;
import iterator.CondExpr;
import iterator.EFileScan;
import iterator.FileScan;
import iterator.FileScanException;
import iterator.FldSpec;
import iterator.IndexNestedLoopsJoins;
import iterator.InvalidRelation;
import iterator.Iterator;
import iterator.JoinsException;
import iterator.LowMemException;
import iterator.NestedLoopsJoins;
import iterator.PredEvalException;
import iterator.RelSpec;
import iterator.Sort;
import iterator.SortException;
import iterator.SortMerge;
import iterator.TupleUtilsException;
import iterator.UnknowAttrType;
import iterator.UnknownKeyTypeException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import zindex.ZTreeFile;

import btree.BTreeFile;
import bufmgr.PageNotReadException;

import nodeheap.Node;
import nodeheap.NodeHeapfile;

/**
 * Implements queries for following expression queries P Q1a ← N N/(N N/) ∗ N N
 * where N N ← (N ode Label|N ode Descriptor) P Q2a ← N N / EN (/ EN ) ∗ where N
 * N ← (N ode Label|N ode Descriptor) & EN ← (Edge Label|M ax Edge W eight) P
 * Q3a ← N N// Bound where N N ← (N ode Label|N ode Descriptor) & Bound ← (M ax
 * N um Edges|M ax T otal Edge W eight) T Qa ← EN ; EN ; EN where EN ← (Edge
 * Label|M ax Edge W eight)
 */
public class PathExpressionQuery {

	public String queryPlan = "";

	/**
	 * Uses the parser that returns the type of query(a,b or c) and an iterator
	 * that satisfies the first node condition for the expression P Q1a ← N N/(N
	 * N/) ∗ N N. Uses the pathexpression to feed each node id along with rest
	 * of the attributes to retrieve tails for that path Adds the result to a
	 * pair to a heap file and feeds it to appropriate result printing type
	 * method.
	 * 
	 * @param pathExpression
	 * @param nhfRef
	 * @param ehfRef
	 * @param btf_edge_src_label
	 * @param btf_node_label
	 * @param ztf_node_desc
	 * @param numBuf
	 * @param nodeLabelLength
	 * @throws InvalidSlotNumberException
	 * @throws InvalidTupleSizeException
	 * @throws Exception
	 */
	public void pathExpressQuery1(String pathExpression, NodeHeapfile nhfRef,
			EdgeHeapFile ehfRef, BTreeFile btf_edge_src_label,
			BTreeFile btf_node_label, ZTreeFile ztf_node_desc, short numBuf,
			short nodeLabelLength) throws InvalidSlotNumberException,
			InvalidTupleSizeException, Exception {

		String nhfName = nhfRef.get_fileName();
		String ehfName = ehfRef.get_fileName();
		String indexEhfSourceNodeName = btf_edge_src_label.get_fileName();
		String indexNodeLabelName = btf_node_label.get_fileName();

		PathExpressionParser parsr = new PathExpressionParser();

		int type = parsr.pathExpressionQuery1Parser(pathExpression,
				btf_node_label, nhfRef, ztf_node_desc);
		AttrType[] attrType = parsr.attrTypeList;
		Object[] expression = parsr.objExpList;
		Iterator sourceNIDs = parsr.niditer;
		PathExpression pathExp = new PathExpression();
		NodeHeapfile nhf = new NodeHeapfile(nhfName);

		Tuple nidTup;
		while ((nidTup = sourceNIDs.get_next()) != null) {

			nidTup.setHdr((short) 1, new AttrType[] { new AttrType(
					AttrType.attrId) }, new short[] {});
			RID newrid = nidTup.getIDFld(1);
			NID newNID = new NID(newrid.pageNo, newrid.slotNo);
			expression[0] = newNID;
			if (((NID) expression[0]).pageNo.pid == -1
					&& ((NID) expression[0]).slotNo == -1) {
				continue;
			}
			Iterator tailNodeIds = pathExp.pathExpress1(type, expression,
					attrType, nhfName, ehfName, indexEhfSourceNodeName,
					indexNodeLabelName, numBuf, nodeLabelLength);
			Tuple tail;

			NID headNID = (NID) expression[0];
			Node headNode = nhf.getRecord(headNID);
			headNode.setHdr();
			Node tailNode;
			Tuple headTailPair = null;
			String prev = "";
			while ((tail = tailNodeIds.get_next()) != null) {
				headTailPair = new Tuple();
				headTailPair.setHdr((short) 3, new AttrType[] {
						new AttrType(AttrType.attrString),
						new AttrType(AttrType.attrString),
						new AttrType(AttrType.attrString) }, new short[] {
						(short) 32, (short) 32, (short) 32 });
				tail.setHdr((short) 1, new AttrType[] { new AttrType(
						AttrType.attrId) }, new short[] {});
				NID tailNid = new NID(tail.getIDFld(1).pageNo,
						tail.getIDFld(1).slotNo);
				if (tailNid == null
						|| (tailNid.pageNo.pid == -1 && tailNid.slotNo == -1)) {
					break;
				}
				tailNode = nhf.getRecord(tailNid);
				tailNode.setHdr();

				if (type != 2) {
					System.out.println("[" + headNode.getLabel() + ", "
							+ tailNode.getLabel() + "]");
				} else {
					if (!prev.equalsIgnoreCase(tailNode.getLabel())) {
						System.out.println("[" + headNode.getLabel() + ", "
								+ tailNode.getLabel() + "]");
					}
					prev = tailNode.getLabel();
				}
			}

			tailNodeIds.close();
			Heapfile tailNodeFile = new Heapfile("TailNodeFileForPQ1");
			tailNodeFile.deleteFile();

		}
		parsr.niditer.close();
		Heapfile sourceNIDfile = new Heapfile("NIDheapfile");
		sourceNIDfile.deleteFile();

		queryPlan = "";
		queryPlan = "(Sigma(NN condition)(NodeHeapFile))";
		for (int i = 1; i < expression.length; i++) {
			queryPlan = "(Sigma(srcCond)" + queryPlan
					+ "|><|(inlj) (EdgeBTSrcFile))";
			queryPlan = "(Sigma(destCond)" + queryPlan
					+ "|><|(inlj) (NodeBTFile))";
		}
	}

	/**
	 * Uses the parser that returns the type of query(a,b or c) and an iterator
	 * that satisfies the first node condition for the expression P Q2a ← N N /
	 * EN (/ EN ) ∗. Uses the pathexpression to feed each node id along with
	 * rest of the attributes to retrieve tails for that path Adds the result to
	 * a pair to a heap file and feeds it to appropriate result printing type
	 * method.
	 * 
	 * @param pathExpression
	 * @param nhfRef
	 * @param ehfRef
	 * @param btf_edge_src_label
	 * @param btf_node_label
	 * @param ztf_node_desc
	 * @param numBuf
	 * @param nodeLabelLength
	 * @throws InvalidSlotNumberException
	 * @throws InvalidTupleSizeException
	 * @throws Exception
	 */
	public void pathExpressQuery2(String pathExpression, NodeHeapfile nhfRef,
			EdgeHeapFile ehfRef, BTreeFile btf_edge_src_label,
			BTreeFile btf_node_label, ZTreeFile ztf_node_desc, short numBuf,
			short nodeLabelLength) throws InvalidSlotNumberException,
			InvalidTupleSizeException, Exception {

		String nhfName = nhfRef.get_fileName();
		String ehfName = ehfRef.get_fileName();
		String indexEhfSourceNodeName = btf_edge_src_label.get_fileName();
		String indexNodeLabelName = btf_node_label.get_fileName();

		PathExpressionParser parsr = new PathExpressionParser();
		int type = parsr.pathExpressionQuery2Parser(pathExpression, nhfRef,
				ztf_node_desc, btf_node_label);
		AttrType[] attrType = parsr.attrTypeList;
		Object[] expression = parsr.objExpList;
		Iterator sourceNIDs = parsr.niditer;
		PathExpression pathExp = new PathExpression();

		NodeHeapfile nhf = new NodeHeapfile(nhfName);
		// Heapfile pathExprQuery2Result = new Heapfile("pathExprQuery2Result");

		Tuple nidTup;
		while ((nidTup = sourceNIDs.get_next()) != null) {

			nidTup.setHdr((short) 1, new AttrType[] { new AttrType(
					AttrType.attrId) }, new short[] {});
			RID newrid = nidTup.getIDFld(1);
			NID newNID = new NID(newrid.pageNo, newrid.slotNo);
			expression[0] = newNID;
			if (((NID) expression[0]).pageNo.pid == -1
					&& ((NID) expression[0]).slotNo == -1) {
				continue;
			}
			Iterator tailNodeIds = pathExp.pathExpress2(type, expression,
					attrType, nhfName, ehfName, indexEhfSourceNodeName,
					indexNodeLabelName, numBuf, nodeLabelLength);
			Tuple tail;
			NID headNID = (NID) expression[0];
			Node headNode = nhf.getRecord(headNID);

			Node tailNode;
			String prev = "";
			while ((tail = tailNodeIds.get_next()) != null) {
				headNode.setHdr();

				Tuple headTailPair = new Tuple();
				headTailPair.setHdr((short) 3, new AttrType[] {
						new AttrType(AttrType.attrString),
						new AttrType(AttrType.attrString),
						new AttrType(AttrType.attrString) }, new short[] {
						(short) 32, (short) 32, (short) 32 });

				tail.setHdr((short) 1, new AttrType[] { new AttrType(
						AttrType.attrId) }, new short[] {});
				NID tailNid = (NID) new NID(tail.getIDFld(1).pageNo,
						tail.getIDFld(1).slotNo);
				if (tailNid == null
						|| (tailNid.pageNo.pid == -1 && tailNid.slotNo == -1)) {
					break;
				}

				tailNode = nhf.getRecord(tailNid);
				tailNode.setHdr();

				if (type != 2) {
					System.out.println("[" + headNode.getLabel() + ", "
							+ tailNode.getLabel() + "]");
				} else {
					if (!prev.equalsIgnoreCase(tailNode.getLabel())) {
						System.out.println("[" + headNode.getLabel() + ", "
								+ tailNode.getLabel() + "]");
					}
					prev = tailNode.getLabel();
				}

			}
			tailNodeIds.close();
			Heapfile tailNodeFile = new Heapfile("TailNodeFileForPQ2");
			tailNodeFile.deleteFile();

		}
		sourceNIDs.close();
		Heapfile sourceNIDfile = new Heapfile("NIDheapfile");
		sourceNIDfile.deleteFile();

		queryPlan = "";
		queryPlan = "(Sigma(srcCond)((Sigma(NN condition)(NodeHeapFile)) |><|(inlj) EdgeBTSrcFile))";
		for (int i = 1; i < expression.length; i++) {
			queryPlan = "(Sigma(dest = src)" + queryPlan
					+ "|><|(inlj) (EdgeBTSrcFile))";
		}

	}

	/**
	 * Uses the parser that returns the type of query(a,b or c) and an iterator
	 * that satisfies the first node condition for the expression P Q3a ← N N//
	 * Bound. Uses the pathexpression to feed each node id along with rest of
	 * the attributes to retrieve tails for that path Adds the result to a pair
	 * to a heap file and feeds it to appropriate result printing type method.
	 * 
	 * @param pathExpression
	 * @param nhfRef
	 * @param ehfRef
	 * @param btf_edge_src_label
	 * @param btf_node_label
	 * @param ztf_node_desc
	 * @param numBuf
	 * @param nodeLabelLength
	 * @throws InvalidSlotNumberException
	 * @throws InvalidTupleSizeException
	 * @throws Exception
	 */
	public void pathExpressQuery3(String pathExpression, NodeHeapfile nhfRef,
			EdgeHeapFile ehfRef, BTreeFile btf_edge_src_label,
			BTreeFile btf_node_label, ZTreeFile ztf_node_desc, short numBuf,
			short nodeLabelLength) throws InvalidSlotNumberException,
			InvalidTupleSizeException, Exception {

		String nhfName = nhfRef.get_fileName();
		String ehfName = ehfRef.get_fileName();
		String indexEhfSourceNodeName = btf_edge_src_label.get_fileName();
		String indexNodeLabelName = btf_node_label.get_fileName();

		PathExpressionParser parsr = new PathExpressionParser();

		int type = parsr.pathExpressionQuery3Parser(pathExpression, nhfRef,
				ztf_node_desc, btf_node_label);
		AttrType[] attrType = parsr.attrTypeList;
		Object[] expression = parsr.objExpList;
		Iterator sourceNIDs = parsr.niditer;
		PathExpression pathExp = new PathExpression();

		NodeHeapfile nhf = new NodeHeapfile(nhfName);
		boolean isMaxWtBound = false;

		if (attrType[1].attrType == AttrType.attrString) {
			isMaxWtBound = true;
		}
		Tuple nidTup;
		while ((nidTup = sourceNIDs.get_next()) != null) {

			nidTup.setHdr((short) 1, new AttrType[] { new AttrType(
					AttrType.attrId) }, new short[] {});
			RID newrid = nidTup.getIDFld(1);
			NID newNID = new NID(newrid.pageNo, newrid.slotNo);
			expression[0] = newNID;
			if (((NID) expression[0]).pageNo.pid == -1
					&& ((NID) expression[0]).slotNo == -1) {
				continue;
			}
			Iterator tailNodeIds = null;
			if (isMaxWtBound) {
				tailNodeIds = pathExp.pathExpress3_2(type, expression, nhfName,
						ehfName, indexEhfSourceNodeName, indexNodeLabelName,
						numBuf, nodeLabelLength);
			} else {
				tailNodeIds = pathExp.pathExpress3_1(type, expression, nhfName,
						ehfName, indexEhfSourceNodeName, indexNodeLabelName,
						numBuf, nodeLabelLength);
			}

			Tuple tail;
			NID headNID = (NID) expression[0];
			Node headNode = nhf.getRecord(headNID);
			headNode.setHdr();
			Node tailNode;
			Tuple headTailPair = null;
			String prev = "";
			while ((tail = tailNodeIds.get_next()) != null) {
				headTailPair = new Tuple();
				headTailPair.setHdr((short) 3, new AttrType[] {
						new AttrType(AttrType.attrString),
						new AttrType(AttrType.attrString),
						new AttrType(AttrType.attrString) }, new short[] {
						(short) 32, (short) 32, (short) 32 });
				tail.setHdr((short) 1, new AttrType[] { new AttrType(
						AttrType.attrId) }, new short[] {});
				NID tailNid = new NID(tail.getIDFld(1).pageNo,
						tail.getIDFld(1).slotNo);
				if (tailNid == null
						|| (tailNid.pageNo.pid == -1 && tailNid.slotNo == -1)) {
					break;
				}
				tailNode = nhf.getRecord(tailNid);
				tailNode.setHdr();

				if (type != 2) {
					System.out.println("[" + headNode.getLabel() + ", "
							+ tailNode.getLabel() + "]");
				} else {
					if (!prev.equalsIgnoreCase(tailNode.getLabel())) {
						System.out.println("[" + headNode.getLabel() + ", "
								+ tailNode.getLabel() + "]");
					}
					prev = tailNode.getLabel();
				}

			}

			tailNodeIds.close();
			Heapfile tailNodeFile = new Heapfile("TailNodeFileForPQ3");
			tailNodeFile.deleteFile();
		}
		sourceNIDs.close();
		Heapfile sourceNIDfile = new Heapfile("NIDheapfile");
		sourceNIDfile.deleteFile();
	}

	/**
	 * retrievs the triangle trio nodes for the path expression T Qa ← EN ; EN ;
	 * EN if a triangular path exists between them
	 * 
	 * @param trianglePathExpression
	 * @param nhfName
	 * @param ehfName
	 * @param indexEhfSourceNodeName
	 * @param indexNodeLabelName
	 * @param numBuf
	 * @param nodeLabelLength
	 * @throws JoinsException
	 * @throws IndexException
	 * @throws InvalidTupleSizeException
	 * @throws InvalidTypeException
	 * @throws PageNotReadException
	 * @throws TupleUtilsException
	 * @throws PredEvalException
	 * @throws SortException
	 * @throws LowMemException
	 * @throws UnknowAttrType
	 * @throws UnknownKeyTypeException
	 * @throws Exception
	 */
	public void triangleQuery(String trianglePathExpression, String nhfName,
			String ehfName, String indexEhfSourceNodeName,
			String indexNodeLabelName, short numBuf, short nodeLabelLength)
			throws JoinsException, IndexException, InvalidTupleSizeException,
			InvalidTypeException, PageNotReadException, TupleUtilsException,
			PredEvalException, SortException, LowMemException, UnknowAttrType,
			UnknownKeyTypeException, Exception {

		PathExpressionParser parsr = new PathExpressionParser();
		AttrType[] attrTypes = new AttrType[3];
		Object[] objExpressions = new Object[3];
		int type = parsr.triangleQueryParser(objExpressions, attrTypes,
				trianglePathExpression);

		// Heapfile triangleQueryResult = new Heapfile("triangleQueryResult");
		Iterator am1 = getTriNodeEdgePair(objExpressions, attrTypes, ehfName,
				indexEhfSourceNodeName, numBuf);

		Iterator am2 = getThirdConnectingEdge(objExpressions, attrTypes,
				ehfName, indexEhfSourceNodeName, am1, numBuf);

		AttrType[] types = new AttrType[7];
		types[0] = new AttrType(AttrType.attrString);
		types[1] = new AttrType(AttrType.attrInteger);
		types[2] = new AttrType(AttrType.attrString);
		types[3] = new AttrType(AttrType.attrString);
		types[4] = new AttrType(AttrType.attrString);
		types[5] = new AttrType(AttrType.attrString);
		types[6] = new AttrType(AttrType.attrString);

		short s1_sizes[] = new short[6];
		s1_sizes[0] = 32;
		s1_sizes[1] = 32;
		s1_sizes[2] = 32;
		s1_sizes[3] = 32;
		s1_sizes[4] = 32;
		s1_sizes[5] = 32;

		AttrType[] types_2 = new AttrType[4];
		types_2[0] = new AttrType(AttrType.attrString);
		types_2[1] = new AttrType(AttrType.attrString);
		types_2[2] = new AttrType(AttrType.attrString);
		types_2[3] = new AttrType(AttrType.attrString);
		short s2_sizes[] = new short[4];
		s2_sizes[0] = 32;
		s2_sizes[1] = 32;
		s2_sizes[2] = 32;
		s2_sizes[3] = 32;

		Tuple finalTrio = new Tuple();
		Tuple tu;
		String prev1 = "";
		String prev2 = "";
		String prev3 = "";
		while ((tu = am2.get_next()) != null) {
			tu.setHdr((short) 7, types, s1_sizes);
			finalTrio.setHdr((short) 4, types_2, s2_sizes);
			String node1 = tu.getStrFld(5);
			String node2 = tu.getStrFld(6);
			String node3 = tu.getStrFld(7);

			if (type != 2) {
				System.out.println("[" + node1 + ", " + node2 + ", " + node3
						+ "]");
			} else {
				if (!(prev1.equalsIgnoreCase(node1)
						&& prev2.equalsIgnoreCase(node2) && prev3
							.equalsIgnoreCase(node3))) {
					System.out.println("[" + node1 + ", " + node2 + ", "
							+ node3 + "]");
				}
				prev1 = node1;
				prev2 = node2;
				prev3 = node3;
			}
			// finalTrio.setStrFld(1, node1);
			// finalTrio.setStrFld(2, node2);
			// finalTrio.setStrFld(3, node3);
			// finalTrio.setStrFld(4, node1 + node2 + node3);
			// triangleQueryResult.insertRecord(finalTrio.getTupleByteArray());

		}

		am2.close();

		// switch (type) {
		// case 0:
		// System.out.println("type a");
		// triangletypeA("triangleQueryResult");
		// break;
		// case 1:
		// System.out.println("type b");
		// triangletypeB("triangleQueryResult", numBuf);
		// break;
		// case 2:
		// System.out.println("type c");
		// triangletypeC("triangleQueryResult", numBuf);
		// break;
		// }
	}

	/**
	 * prints the unique triangle node trio from the given file in sorted order.
	 * considering trio combined string and applying sort on this combined
	 * string and checking if the previous printed trio is not equal to the
	 * current one.
	 * 
	 * @param fileName
	 * @param numBuf
	 * @throws JoinsException
	 * @throws IndexException
	 * @throws InvalidTupleSizeException
	 * @throws InvalidTypeException
	 * @throws PageNotReadException
	 * @throws PredEvalException
	 * @throws SortException
	 * @throws LowMemException
	 * @throws UnknowAttrType
	 * @throws UnknownKeyTypeException
	 * @throws Exception
	 */
	// private void triangletypeC(String fileName, short numBuf)
	// throws JoinsException, IndexException, InvalidTupleSizeException,
	// InvalidTypeException, PageNotReadException, PredEvalException,
	// SortException, LowMemException, UnknowAttrType,
	// UnknownKeyTypeException, Exception {
	// Heapfile triExprQueryResult = new Heapfile(fileName);
	//
	// AttrType[] type = new AttrType[4];
	// type[0] = new AttrType(AttrType.attrString);
	// type[1] = new AttrType(AttrType.attrString);
	// type[2] = new AttrType(AttrType.attrString);
	// type[3] = new AttrType(AttrType.attrString);
	// short[] str_sizes = new short[4];
	// str_sizes[0] = (short) 32;
	// str_sizes[1] = (short) 32;
	// str_sizes[2] = (short) 32;
	// str_sizes[3] = (short) 32;
	// FldSpec[] proj_list = new FldSpec[4];
	// proj_list[0] = new FldSpec(new RelSpec(RelSpec.outer), 1);
	// proj_list[1] = new FldSpec(new RelSpec(RelSpec.outer), 2);
	// proj_list[2] = new FldSpec(new RelSpec(RelSpec.outer), 3);
	// proj_list[3] = new FldSpec(new RelSpec(RelSpec.outer), 4);
	//
	// Iterator resultScanner = new FileScan(fileName, type, str_sizes,
	// (short) 4, (short) 4, proj_list, null);
	//
	// Iterator sort = new Sort(type, (short) 4, str_sizes, resultScanner, 4,
	// new TupleOrder(0), 32, numBuf);
	//
	// boolean noOutput = true;
	// Tuple prevRes = null;
	// Tuple res;
	// while ((res = sort.get_next()) != null) {
	// noOutput = false;
	// res.setHdr((short) 4, type, str_sizes);
	// if (prevRes == null
	// || !(prevRes.getStrFld(1)
	// .equalsIgnoreCase(res.getStrFld(1))
	// && prevRes.getStrFld(2).equalsIgnoreCase(
	// res.getStrFld(2)) && prevRes.getStrFld(3)
	// .equalsIgnoreCase(res.getStrFld(3)))) {
	// prevRes = new Tuple(res);
	// System.out.println("[" + res.getStrFld(1) + ", "
	// + res.getStrFld(2) + ", " + res.getStrFld(3) + "]");
	// }
	// }
	//
	// if (noOutput) {
	// System.out
	// .println("No Nodes satisfy the given triangle query expression.");
	// }
	// sort.close();
	// triExprQueryResult.deleteFile();
	// queryPlan = "";
	// queryPlan =
	// "Sigma(Unique)(Sort-tiplet combined(Sigma(dest3 == src1 && src3 == dest2)(Sigma(2nd edge condition)(Sigma(1st edge condition)(EdgeHeapFile)) |><|(inlj) (EdgeHeapFile)) |><|(sort-merge) (EdgeHeapFile)))";
	// System.out.println(queryPlan);
	// }
	//
	// /**
	// * prints the triangular nodes trio from the given file in sorted order.
	// * considering trio combined string and applying sort on this combined
	// * string.
	// *
	// * @param fileName
	// * @param numBuf
	// * @throws JoinsException
	// * @throws IndexException
	// * @throws PageNotReadException
	// * @throws PredEvalException
	// * @throws LowMemException
	// * @throws UnknowAttrType
	// * @throws UnknownKeyTypeException
	// * @throws FieldNumberOutOfBoundException
	// * @throws Exception
	// */
	// private void triangletypeB(String fileName, short numBuf)
	// throws JoinsException, IndexException, PageNotReadException,
	// PredEvalException, LowMemException, UnknowAttrType,
	// UnknownKeyTypeException, FieldNumberOutOfBoundException, Exception {
	// Heapfile triExprQueryResult = new Heapfile(fileName);
	// AttrType[] type = new AttrType[4];
	// type[0] = new AttrType(AttrType.attrString);
	// type[1] = new AttrType(AttrType.attrString);
	// type[2] = new AttrType(AttrType.attrString);
	// type[3] = new AttrType(AttrType.attrString);
	// short[] str_sizes = new short[4];
	// str_sizes[0] = (short) 32;
	// str_sizes[1] = (short) 32;
	// str_sizes[2] = (short) 32;
	// str_sizes[3] = (short) 32;
	// FldSpec[] proj_list = new FldSpec[4];
	// proj_list[0] = new FldSpec(new RelSpec(RelSpec.outer), 1);
	// proj_list[1] = new FldSpec(new RelSpec(RelSpec.outer), 2);
	// proj_list[2] = new FldSpec(new RelSpec(RelSpec.outer), 3);
	// proj_list[3] = new FldSpec(new RelSpec(RelSpec.outer), 4);
	//
	// Iterator resultScanner = new FileScan(fileName, type, str_sizes,
	// (short) 4, (short) 4, proj_list, null);
	//
	// Iterator sort = new Sort(type, (short) 4, str_sizes, resultScanner, 4,
	// new TupleOrder(0), 32, numBuf);
	// boolean noOutput = true;
	// Tuple res;
	// while ((res = sort.get_next()) != null) {
	// noOutput = false;
	// res.setHdr((short) 4, type, str_sizes);
	// System.out.println("[" + res.getStrFld(1) + ", " + res.getStrFld(2)
	// + ", " + res.getStrFld(3) + "]");
	// }
	//
	// if (noOutput) {
	// System.out
	// .println("No Nodes satisfy the given triangle query expression.");
	// }
	//
	// sort.close();
	// triExprQueryResult.deleteFile();
	// queryPlan = "";
	// queryPlan =
	// "Sort-tiplet combined(Sigma(dest3 == src1 && src3 == dest2)(Sigma(2nd edge condition)(Sigma(1st edge condition)(EdgeHeapFile)) |><|(inlj) (EdgeHeapFile)) |><|(inlj) (EdgeHeapFile))";
	// System.out.println(queryPlan);
	// }
	//
	// /**
	// * prints the triangular node trios from the given file
	// *
	// * @param fileName
	// * @throws JoinsException
	// * @throws IndexException
	// * @throws PageNotReadException
	// * @throws PredEvalException
	// * @throws SortException
	// * @throws LowMemException
	// * @throws UnknowAttrType
	// * @throws UnknownKeyTypeException
	// * @throws Exception
	// */
	// private void triangletypeA(String fileName) throws JoinsException,
	// IndexException, PageNotReadException, PredEvalException,
	// SortException, LowMemException, UnknowAttrType,
	// UnknownKeyTypeException, Exception {
	// Heapfile triExprQueryResult = new Heapfile(fileName);
	// AttrType[] outtype = new AttrType[3];
	// outtype[0] = new AttrType(AttrType.attrString);
	// outtype[1] = new AttrType(AttrType.attrString);
	// outtype[2] = new AttrType(AttrType.attrString);
	// short[] out_str_sizes = new short[3];
	// out_str_sizes[0] = (short) 32;
	// out_str_sizes[1] = (short) 32;
	// out_str_sizes[2] = (short) 32;
	//
	// AttrType[] type = new AttrType[4];
	// type[0] = new AttrType(AttrType.attrString);
	// type[1] = new AttrType(AttrType.attrString);
	// type[2] = new AttrType(AttrType.attrString);
	// type[3] = new AttrType(AttrType.attrString);
	// short[] str_sizes = new short[4];
	// str_sizes[0] = (short) 32;
	// str_sizes[1] = (short) 32;
	// str_sizes[2] = (short) 32;
	// str_sizes[3] = (short) 32;
	// FldSpec[] proj_list = new FldSpec[3];
	// proj_list[0] = new FldSpec(new RelSpec(RelSpec.outer), 1);
	// proj_list[1] = new FldSpec(new RelSpec(RelSpec.outer), 2);
	// proj_list[2] = new FldSpec(new RelSpec(RelSpec.outer), 3);
	//
	// Iterator resultScanner = new FileScan(fileName, type, str_sizes,
	// (short) 4, (short) 3, proj_list, null);
	//
	// boolean noOutput = true;
	//
	// Tuple res;
	// while ((res = resultScanner.get_next()) != null) {
	// noOutput = false;
	// res.setHdr((short) 3, outtype, out_str_sizes);
	// res.print(outtype);
	// }
	//
	// if (noOutput) {
	// System.out
	// .println("No Nodes satisfy the given triangle query expression.");
	// }
	// resultScanner.close();
	// triExprQueryResult.deleteFile();
	// queryPlan = "";
	// queryPlan =
	// "(Sigma(dest3 == src1 && src3 == dest2)(Sigma(2nd edge condition)(Sigma(1st edge condition)(EdgeHeapFile)) |><|(inlj) (EdgeHeapFile)) |><|(inlj) (EdgeHeapFile))";
	// System.out.println(queryPlan);
	// }

	/**
	 * returns the joined edges that satisfy the condition for destination node
	 * from outer is equal to the source node from inner. And satisfy the first
	 * 2 condition for edge label or weight from T Qa ← EN ; EN ; EN expression
	 * 
	 * @param objExpressions
	 * @param attrTypes
	 * @param ehfName
	 * @param numBuf
	 * @return
	 */
	private Iterator getTriNodeEdgePair(Object[] objExpressions,
			AttrType[] attrTypes, String ehfName,
			String indexEhfSourceNodeName, int numBuf) {

		AttrType[] attrType = new AttrType[8];
		attrType[0] = new AttrType(AttrType.attrInteger); // SrcNID.pageid
		attrType[1] = new AttrType(AttrType.attrInteger); // SrcNID.slotno
		attrType[2] = new AttrType(AttrType.attrInteger); // DestNID.pageid
		attrType[3] = new AttrType(AttrType.attrInteger); // DestNID.slotno
		attrType[4] = new AttrType(AttrType.attrString); // EdgeLabel
		attrType[5] = new AttrType(AttrType.attrInteger); // EdgeWeight
		attrType[6] = new AttrType(AttrType.attrString); // SrcLabel
		attrType[7] = new AttrType(AttrType.attrString); // DestLabel

		AttrType[] jtype = new AttrType[8];
		jtype[0] = new AttrType(AttrType.attrString); // EdgeLabel1
		jtype[1] = new AttrType(AttrType.attrInteger); // EdgeWeight1
		jtype[2] = new AttrType(AttrType.attrString); // SrcLabel1
		jtype[3] = new AttrType(AttrType.attrString); // DestLabel1

		jtype[4] = new AttrType(AttrType.attrString); // EdgeLabel
		jtype[5] = new AttrType(AttrType.attrInteger); // EdgeWeight
		jtype[6] = new AttrType(AttrType.attrString); // SrcLabel
		jtype[7] = new AttrType(AttrType.attrString); // DestLabel

		FldSpec[] inputProjList = new FldSpec[8];
		RelSpec rel1 = new RelSpec(RelSpec.outer);
		RelSpec rel2 = new RelSpec(RelSpec.innerRel);
		inputProjList[0] = new FldSpec(rel1, 1);
		inputProjList[1] = new FldSpec(rel1, 2);
		inputProjList[2] = new FldSpec(rel1, 3);
		inputProjList[3] = new FldSpec(rel1, 4);
		inputProjList[4] = new FldSpec(rel1, 5);
		inputProjList[5] = new FldSpec(rel1, 6);
		inputProjList[6] = new FldSpec(rel1, 7);
		inputProjList[7] = new FldSpec(rel1, 8);

		FldSpec[] outputProjList = new FldSpec[8];
		outputProjList[0] = new FldSpec(rel1, 5);
		outputProjList[1] = new FldSpec(rel1, 6);
		outputProjList[2] = new FldSpec(rel1, 7);
		outputProjList[3] = new FldSpec(rel1, 8);
		outputProjList[4] = new FldSpec(rel2, 5);
		outputProjList[5] = new FldSpec(rel2, 6);
		outputProjList[6] = new FldSpec(rel2, 7);
		outputProjList[7] = new FldSpec(rel2, 8);

		short s1_sizes[] = new short[3];
		s1_sizes[0] = 32;
		s1_sizes[1] = 32;
		s1_sizes[2] = 32;

		CondExpr[] outexpr = new CondExpr[2];
		outexpr[0] = new CondExpr();
		outexpr[0].next = null;
		outexpr[0].type2 = new AttrType(AttrType.attrSymbol);
		if (attrTypes[0].attrType == AttrType.attrString) {
			outexpr[0].op = new AttrOperator(AttrOperator.aopEQ);
			outexpr[0].type1 = new AttrType(AttrType.attrString);
			outexpr[0].operand2.symbol = new FldSpec(
					new RelSpec(RelSpec.outer), 5);
			outexpr[0].operand1.string = (String) objExpressions[0];
		} else {
			outexpr[0].op = new AttrOperator(AttrOperator.aopGE);
			outexpr[0].type1 = new AttrType(AttrType.attrInteger);
			outexpr[0].operand2.symbol = new FldSpec(
					new RelSpec(RelSpec.outer), 6);
			outexpr[0].operand1.integer = (Integer) objExpressions[0];
		}
		outexpr[1] = null;

		CondExpr[] expr = new CondExpr[2];
		// expr[0] = new CondExpr();
		// expr[0].next = null;
		// expr[0].op = new AttrOperator(AttrOperator.aopEQ);
		// expr[0].type1 = new AttrType(AttrType.attrSymbol);
		// expr[0].operand1.symbol = new FldSpec(new RelSpec(RelSpec.outer), 8);
		// expr[0].type2 = new AttrType(AttrType.attrSymbol);
		// expr[0].operand2.symbol = new FldSpec(new RelSpec(RelSpec.innerRel),
		// 7);
		expr[0] = new CondExpr();
		expr[0].next = null;
		expr[0].type2 = new AttrType(AttrType.attrSymbol);
		if (attrTypes[1].attrType == AttrType.attrString) {
			expr[0].op = new AttrOperator(AttrOperator.aopEQ);
			expr[0].type1 = new AttrType(AttrType.attrString);
			expr[0].operand2.symbol = new FldSpec(
					new RelSpec(RelSpec.innerRel), 5);
			expr[0].operand1.string = (String) objExpressions[1];
		} else {
			expr[0].op = new AttrOperator(AttrOperator.aopGE);
			expr[0].type1 = new AttrType(AttrType.attrInteger);
			expr[0].operand2.symbol = new FldSpec(
					new RelSpec(RelSpec.innerRel), 6);
			expr[0].operand1.integer = (Integer) objExpressions[1];
		}
		expr[1] = null;

		TupleOrder order = new TupleOrder(TupleOrder.Ascending);
		Iterator sm = null;

		try {
			System.out.println("prev = (EdgeHeapFile |><|(sm) EdgeHeapFile)");
			EdgeIndexScan eIscan1 = new EdgeIndexScan(new IndexType(1),
					ehfName, indexEhfSourceNodeName, attrType, s1_sizes, 8, 8,
					inputProjList, null, 7, false);
			EFileScan efscan2 = new EFileScan(ehfName, attrType, s1_sizes,
					(short) 8, 8, inputProjList, null);
			sm = new SortMerge(attrType, 8, s1_sizes, attrType, 8, s1_sizes, 8,
					32, 7, 32, numBuf, eIscan1, efscan2, false, false, order,
					expr, outputProjList, outputProjList.length);

		} catch (Exception e) {
			e.printStackTrace();
		}
		return sm;
	}

	/**
	 * returns the joined edges that satisfy the condition for destination node
	 * from outer is equal to the source node from inner. And satisfy the 3rd
	 * condition for edge label or weight from T Qa ← EN ; EN ; EN expression
	 * 
	 * @param objExpressions
	 * @param attrTypes
	 * @param ehfName
	 * @param indexEhfSourceNodeName
	 * @param am1
	 * @param numBuf
	 * @return
	 */
	private Iterator getThirdConnectingEdge(Object[] objExpressions,
			AttrType[] attrTypes, String ehfName,
			String indexEhfSourceNodeName, Iterator am1, int numBuf) {

		AttrType[] attrType = new AttrType[8];
		attrType[0] = new AttrType(AttrType.attrInteger); // SrcNID.pageid
		attrType[1] = new AttrType(AttrType.attrInteger); // SrcNID.slotno
		attrType[2] = new AttrType(AttrType.attrInteger); // DestNID.pageid
		attrType[3] = new AttrType(AttrType.attrInteger); // DestNID.slotno
		attrType[4] = new AttrType(AttrType.attrString); // EdgeLabel
		attrType[5] = new AttrType(AttrType.attrInteger); // EdgeWeight
		attrType[6] = new AttrType(AttrType.attrString); // SrcLabel
		attrType[7] = new AttrType(AttrType.attrString); // DestLabel

		AttrType[] jtype = new AttrType[8];
		jtype[0] = new AttrType(AttrType.attrString); // EdgeLabel1
		jtype[1] = new AttrType(AttrType.attrInteger); // EdgeWeight1
		jtype[2] = new AttrType(AttrType.attrString); // SrcLabel1
		jtype[3] = new AttrType(AttrType.attrString); // DestLabel1

		jtype[4] = new AttrType(AttrType.attrString); // EdgeLabel
		jtype[5] = new AttrType(AttrType.attrInteger); // EdgeWeight
		jtype[6] = new AttrType(AttrType.attrString); // SrcLabel
		jtype[7] = new AttrType(AttrType.attrString); // DestLabel

		FldSpec[] inputProjList = new FldSpec[8];
		RelSpec rel1 = new RelSpec(RelSpec.outer);
		RelSpec rel2 = new RelSpec(RelSpec.innerRel);
		inputProjList[0] = new FldSpec(rel1, 1);
		inputProjList[1] = new FldSpec(rel1, 2);
		inputProjList[2] = new FldSpec(rel1, 3);
		inputProjList[3] = new FldSpec(rel1, 4);
		inputProjList[4] = new FldSpec(rel1, 5);
		inputProjList[5] = new FldSpec(rel1, 6);
		inputProjList[6] = new FldSpec(rel1, 7);
		inputProjList[7] = new FldSpec(rel1, 8);

		FldSpec[] outputProjList = new FldSpec[7];
		outputProjList[0] = new FldSpec(rel2, 5);
		outputProjList[1] = new FldSpec(rel2, 6);
		outputProjList[2] = new FldSpec(rel2, 7);
		outputProjList[3] = new FldSpec(rel2, 8);
		outputProjList[4] = new FldSpec(rel1, 3);
		outputProjList[5] = new FldSpec(rel1, 4);
		outputProjList[6] = new FldSpec(rel1, 8);

		short s2_sizes[] = new short[3];
		s2_sizes[0] = 32;
		s2_sizes[1] = 32;
		s2_sizes[2] = 32;

		short s1_sizes[] = new short[6];
		s1_sizes[0] = 32;
		s1_sizes[1] = 32;
		s1_sizes[2] = 32;
		s1_sizes[3] = 32;
		s1_sizes[4] = 32;
		s1_sizes[5] = 32;

		CondExpr[] expr = new CondExpr[4];
		expr[0] = new CondExpr();
		expr[0].next = null;
		expr[0].op = new AttrOperator(AttrOperator.aopEQ);
		expr[0].type1 = new AttrType(AttrType.attrSymbol);
		expr[0].operand1.symbol = new FldSpec(new RelSpec(RelSpec.outer), 3);
		expr[0].type2 = new AttrType(AttrType.attrSymbol);
		expr[0].operand2.symbol = new FldSpec(new RelSpec(RelSpec.innerRel), 8);

		expr[1] = new CondExpr();
		expr[1].next = null;
		expr[1].op = new AttrOperator(AttrOperator.aopEQ);
		expr[1].type1 = new AttrType(AttrType.attrSymbol);
		expr[1].operand1.symbol = new FldSpec(new RelSpec(RelSpec.outer), 8);
		expr[1].type2 = new AttrType(AttrType.attrSymbol);
		expr[1].operand2.symbol = new FldSpec(new RelSpec(RelSpec.innerRel), 7);

		expr[2] = new CondExpr();
		expr[2].next = null;
		expr[2].type2 = new AttrType(AttrType.attrSymbol);
		if (attrTypes[2].attrType == AttrType.attrString) {
			expr[2].op = new AttrOperator(AttrOperator.aopEQ);
			expr[2].type1 = new AttrType(AttrType.attrString);
			expr[2].operand2.symbol = new FldSpec(
					new RelSpec(RelSpec.innerRel), 5);
			expr[2].operand1.string = (String) objExpressions[2];
		} else {
			expr[2].op = new AttrOperator(AttrOperator.aopGE);
			expr[2].type1 = new AttrType(AttrType.attrInteger);
			expr[2].operand2.symbol = new FldSpec(
					new RelSpec(RelSpec.innerRel), 6);
			expr[2].operand1.integer = (Integer) objExpressions[2];
		}
		expr[3] = null;

		TupleOrder order = new TupleOrder(TupleOrder.Ascending);
		Iterator sm = null;

		try {
			System.out.println("prev |><|(sm) EdgeHeapFile)");

			EFileScan efscan2 = new EFileScan(ehfName, attrType, s2_sizes,
					(short) 8, 8, inputProjList, null);
			sm = new SortMerge(jtype, 8, s1_sizes, attrType, 8, s2_sizes, 8,
					32, 7, 32, numBuf, am1, efscan2, false, false, order, expr,
					outputProjList, outputProjList.length);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return sm;
	}
}
