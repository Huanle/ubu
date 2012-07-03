package edu.unc.bioinf.ubu.assembly;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.sf.samtools.SAMRecord;


public class Node {

	private String sequence;
	private int count = 1;
	private Map<Node, Edge> toEdges = new HashMap<Node, Edge>();
	private Map<Node, Edge> fromEdges = new HashMap<Node, Edge>();
	
	private Set<SAMRecord> startingReads = new HashSet<SAMRecord>();
	
	private String source;
	
	public Node(String sequence, String source) {
		this.sequence = sequence;
		this.source = source;
	}
	
	public String toString() {
		return count + "_" + sequence;
	}
	
	public int hashCode() {
		return sequence.hashCode();
	}
	
	public boolean equals(Object object) {
		Node that = (Node) object;
		return this.sequence.equals(that.sequence);
	}
	
	public void incrementCount() {
		count++;
	}
	
	public int getCount() {
		return count;
	}
	
	public Collection<Edge> getToEdges() {
		return toEdges.values();
	}
	
	public Collection<Edge> getFromEdges() {
		return fromEdges.values();
	}
	
	public String getSequence() {
		return sequence;
	}
	
	public void addStartingRead(SAMRecord read) {
		this.startingReads.add(read);
	}
	
	public String getSource() {
		return source;
	}
	
	private void printMultiEdges() {
		int aboveThreshold = 0;
		
		for (Edge edge : toEdges.values()) {
			if (edge.getCount() > 50) {
				aboveThreshold++;
			}
		}
		
		if (aboveThreshold > 1) {
			System.out.println("------- Edge --------");
			for (Edge edge : toEdges.values()) {
				System.out.print(edge.getCount() + ", ");
			}
			
			System.out.println();
		}
	}
	
	public List<Edge> getInfrequentEdges(double minFreq) {
		List<Edge> infrequentEdges = new ArrayList<Edge>();
		
		infrequentEdges.addAll(getInfrequentEdges(minFreq, toEdges.values()));
		infrequentEdges.addAll(getInfrequentEdges(minFreq, fromEdges.values()));
		
		return infrequentEdges;
	}
	
	private List<Edge> getInfrequentEdges(double minFreq, Collection<Edge> edges) {
		List<Edge> infrequentEdges = new ArrayList<Edge>();
		
		double total = getEdgeTotal(edges);
				
		for (Edge edge : edges) {
			if (((double) edge.getCount() / total) < minFreq) {
				infrequentEdges.add(edge);
			}
		}
		
		return infrequentEdges;
	}
	
	private double getEdgeTotal(Collection<Edge> edges) {
		double total = 0.0;
		
		for (Edge edge : edges) {
			total += edge.getCount();
		}

		return total;
	}
	
	public List<Edge> getFrequentToEdges(double minFreq) {
		
		List<Edge> frequentEdges = new ArrayList<Edge>();
		
		double total = getEdgeTotal(toEdges.values());
		
		for (Edge edge : toEdges.values()) {
			if (((double) edge.getCount() / total) >= minFreq) {
				frequentEdges.add(edge);
			}
		}
		
		return frequentEdges;
	}
	
	public Edge getMostCommonEdge() {
		printMultiEdges();
		
		Edge topEdge = null;
		int freq = 0;
		
//		if (toEdges.size() > 1)
//			System.out.println("------- Edge --------");
		
		for (Edge edge : toEdges.values()) {
//			if (toEdges.size() > 1) 
//				System.out.print(edge.getCount() + ", ");
			
			if (edge.getCount() > freq) {
				topEdge = edge;
				freq = edge.getCount();
			}
		}
		
//		if (toEdges.size() > 1)
//			System.out.println();
		
		return topEdge;
	}
	
	public void addToEdge(Node to) {
		Edge edge = toEdges.get(to);
		
		if (edge == null) {
			edge = new Edge(this, to);
			toEdges.put(to, edge);
			to.updateFromEdges(edge);
		} else {
			edge.incrementCount();
		}
	}
	
	public boolean isRootNode() {
		return fromEdges.isEmpty();
	}
	
	private void updateFromEdges(Edge edge) {
		fromEdges.put(edge.getFrom(), edge);
	}
	
	public boolean isSingleton() {
		return fromEdges.isEmpty() && toEdges.isEmpty();
	}
	
	public void removeToEdge(Edge edge) {
		this.toEdges.remove(edge.getTo());
	}
	
	public void removeFromEdge(Edge edge) {
		this.fromEdges.remove(edge.getFrom());
	}
	
	public Set<SAMRecord> getStartingReads() {
		return Collections.unmodifiableSet(startingReads);
	}
}
