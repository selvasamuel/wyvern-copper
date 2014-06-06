package edu.umn.cs.melt.copper.compiletime.builders;

import java.util.BitSet;

import edu.umn.cs.melt.copper.compiletime.lrdfa.LR0DFA;
import edu.umn.cs.melt.copper.compiletime.lrdfa.LR0ItemSet;
import edu.umn.cs.melt.copper.compiletime.lrdfa.LRLookaheadAndLayoutSets;
import edu.umn.cs.melt.copper.compiletime.spec.numeric.ContextSets;
import edu.umn.cs.melt.copper.compiletime.spec.numeric.ParserSpec;

/**
 * Annotates an existing LR(0) DFA with LALR(1) lookahead and valid layout sets
 * (generated by the same method for reasons of efficiency).
 * @author August Schwerdfeger &lt;<a href="mailto:schwerdf@cs.umn.edu">schwerdf@cs.umn.edu</a>&gt;
 *
 */
public class LALRLookaheadAndLayoutSetBuilder
{
	private ParserSpec spec;
	private ContextSets contextSets;
	private LR0DFA dfa;
	private BitSet[][] beginningLayout;
	private BitSet[][] lookaheadLayout;
	
	private BitSet lookaheadLayoutBuffer;
	
	private LALRLookaheadAndLayoutSetBuilder(ParserSpec spec,ContextSets contextSets,LR0DFA dfa)
	{
		this.spec = spec;
		this.contextSets = contextSets;
		this.dfa = dfa;
		
		lookaheadLayoutBuffer = new BitSet();
	}
	
	public static LRLookaheadAndLayoutSets build(ParserSpec spec,ContextSets contextSets,LR0DFA dfa)
	{
		return new LALRLookaheadAndLayoutSetBuilder(spec,contextSets,dfa).buildLA();
	}
	
	private LRLookaheadAndLayoutSets buildLA()
	{
		LRLookaheadAndLayoutSets lookaheadSets = new LRLookaheadAndLayoutSets(dfa);
		beginningLayout = new BitSet[dfa.size()][lookaheadSets.getMaxItemCount()];
		lookaheadLayout = new BitSet[dfa.size()][lookaheadSets.getMaxItemCount()];
		
		for(int i = 0;i < dfa.size();i++)
		{
			for(int j = 0;j < dfa.getItemSet(i).size();j++)
			{
				beginningLayout[i][j] = new BitSet(spec.terminals.length());
				lookaheadLayout[i][j] = new BitSet(spec.terminals.length());
			}
		}
		
		BitSet activeTransitions = new BitSet();
		
		// States that have had changes to a lookahead set
		// in the current or previous iteration.
		BitSet[] fringeStates;
		// Items that have had changes to a lookahead set
		// in the current or previous iteration. Columns
		// are state indices.
		BitSet[][] fringeItems;
		
		fringeStates = new BitSet[2];
		fringeItems = new BitSet[2][dfa.size()];
		
		fringeStates[0] = new BitSet();
		fringeStates[1] = new BitSet();
		for(int i = 0;i < dfa.size();i++)
		{
			fringeItems[0][i] = new BitSet();
			fringeItems[1][i] = new BitSet();
		}
		int lastFringe = 1,currentFringe = 0;
		
		// Start in the DFA's start state,
		fringeStates[lastFringe].set(1);
		// with the initial item, ^ ::= S $.
		fringeItems[lastFringe][1].set(0);
		
		//beginningLayout[1][0] = new BitSet();
		beginningLayout[1][0].or(spec.p.getLayout());
		
		boolean setsChanged = true;
		while(setsChanged)
		{
			setsChanged = false;
			// For each state I in the fringe from the last iteration:
			for(int I = fringeStates[lastFringe].nextSetBit(0);I >= 0;I = fringeStates[lastFringe].nextSetBit(I+1))
			{
				// The group of items in the state in which a lookahead set was changed.
				BitSet seedItems = fringeItems[lastFringe][I];
				
				computeLookaheadClosure(lookaheadSets,I,seedItems);
				
				activeTransitions.clear();
				
				for(int item = seedItems.nextSetBit(0);item >= 0;item = seedItems.nextSetBit(item+1))
				{
					if(dfa.getItemSet(I).getPosition(item) < spec.pr.getRHSLength(dfa.getItemSet(I).getProduction(item)))
					{
						int X = spec.pr.getRHSSym(dfa.getItemSet(I).getProduction(item),dfa.getItemSet(I).getPosition(item));
						if(X != spec.getEOFTerminal())
						{
							activeTransitions.set(X);
						}
					}
				}
				for(int X = activeTransitions.nextSetBit(0);X >= 0;X = activeTransitions.nextSetBit(X+1))
				{
					int J = dfa.getTransition(I,X);
					for(int i = 0,item = dfa.getGotoItems(I,X).nextSetBit(0);item >= 0;i++,item = dfa.getGotoItems(I,X).nextSetBit(item+1))
					{
						if(seedItems.get(item))
						{
							boolean changed = false;
							changed |= ParserSpec.union(lookaheadSets.getLookahead(J,i),lookaheadSets.getLookahead(I,item));
							changed |= ParserSpec.union(beginningLayout[J][i],beginningLayout[I][item]);
							changed |= ParserSpec.union(lookaheadLayout[J][i],lookaheadLayout[I][item]);
							if(dfa.getItemSet(J).getPosition(i) < spec.pr.getRHSLength(dfa.getItemSet(J).getProduction(i)))
							{
								ParserSpec.union(lookaheadSets.getLayout(J),spec.pr.getLayouts(dfa.getItemSet(J).getProduction(i)));
							}
							if(changed)
							{
								fringeStates[currentFringe].set(J);
								fringeItems[currentFringe][J].set(i);
							}
						}
					}
				}
				
				seedItems.clear();

			}
			fringeStates[lastFringe].clear();

			if(!fringeStates[currentFringe].isEmpty())
			{
				currentFringe = (currentFringe == 0) ? 1 : 0;
				lastFringe = (lastFringe == 0) ? 1 : 0;
				setsChanged = true;
			}
		}

		return lookaheadSets;
	}
	
	// Applies the X ::= a (*) b c,   d   ==> b ::= e (*) f,    first(cd) rule.
	private void computeLookaheadClosure(LRLookaheadAndLayoutSets lookaheadSets,int state,BitSet seedItems)
	{
		LR0ItemSet stateI = dfa.getItemSet(state);
		
		BitSet fringe1 = new BitSet(),fringe2 = new BitSet();
		BitSet combinedFirst = new BitSet();
		
		fringe1.or(seedItems);
		
		boolean setsChanged = true;
		while(setsChanged)
		{
			setsChanged = false;
			for(int item = fringe1.nextSetBit(0);item >= 0;item = fringe1.nextSetBit(item+1))
			{
				combinedFirst.clear();
				lookaheadLayoutBuffer.clear();
				boolean useLookahead = computeCombinedFirst(stateI.getProduction(item),stateI.getPosition(item),combinedFirst);
				
				if(stateI.getPosition(item) == 0)
				{
					lookaheadSets.getLayout(state).or(beginningLayout[state][item]);
				}
				else if(stateI.getPosition(item) < spec.pr.getRHSLength(stateI.getProduction(item)))
				{
					lookaheadLayoutBuffer.or(spec.pr.getLayouts(stateI.getProduction(item)));
				}
				
				if(useLookahead)
				{
					if(stateI.getPosition(item) == spec.pr.getRHSLength(stateI.getProduction(item)) ||
				       contextSets.isNullable(spec.pr.getRHSSym(stateI.getProduction(item),stateI.getPosition(item))))
				    {
						lookaheadSets.getLayout(state).or(lookaheadLayout[state][item]);
				    }
					lookaheadLayoutBuffer.or(lookaheadLayout[state][item]);
				}
				
				if(stateI.getPosition(item) >= spec.pr.getRHSLength(stateI.getProduction(item))) continue;

				// BOTTLENECK -- could speed this up by having a map of productions
				// e.g. A -> b (*) C d  -->   C -> e
				for(int i = 0;i < stateI.size();i++)
				{
					if(stateI.getPosition(i) == 0 &&
					   spec.pr.getLHS(stateI.getProduction(i)) == spec.pr.getRHSSym(stateI.getProduction(item),stateI.getPosition(item)))
					{
						boolean setChanged = false;
						
						setChanged |= ParserSpec.union(lookaheadSets.getLookahead(state,i),combinedFirst);
						
						if(stateI.getPosition(item) == 0)
						{
							setChanged |= ParserSpec.union(beginningLayout[state][i],beginningLayout[state][item]);
						}
						else
						{
							setChanged |= ParserSpec.union(beginningLayout[state][i],spec.pr.getLayouts(stateI.getProduction(item)));
						}

						if(spec.pr.getRHSLength(stateI.getProduction(i)) > 0 &&
						   spec.terminals.get(spec.pr.getRHSSym(stateI.getProduction(i),0)))
						{
							ParserSpec.union(lookaheadSets.getLayout(state),beginningLayout[state][i]);
						}

						setChanged |= ParserSpec.union(lookaheadLayout[state][i],lookaheadLayoutBuffer);
						if(useLookahead)
						{
							setChanged |= ParserSpec.union(lookaheadSets.getLookahead(state,i),lookaheadSets.getLookahead(state,item));
						}
						if(setChanged)
						{
							setsChanged = true;
							fringe2.set(i);
						}
					}
				}
			}
			seedItems.or(fringe2);
			fringe1.clear();
			fringe1.or(fringe2);
		}
	}
	
	// Puts all the symbols of the "combined first" into the bit-set 'combinedFirst',
	// except for the lookahead; if all the symbols after 'position' are nullable
	// it will return 'true', indicating that the lookahead is also part of the
	// "combined first."
	private boolean computeCombinedFirst(int production,int position,BitSet combinedFirst)
	{
		boolean stillNullable = true;
		lookaheadLayoutBuffer.clear();
		
		for(int i = position+1;i < spec.pr.getRHSLength(production) && stillNullable;i++)
		{
			BitSet first = contextSets.getFirst(spec.pr.getRHSSym(production,i));
			combinedFirst.or(first);
			if(lookaheadLayoutBuffer.isEmpty() && !first.isEmpty())
			{
				lookaheadLayoutBuffer.or(spec.pr.getLayouts(production));
			}
			stillNullable &= contextSets.isNullable(spec.pr.getRHSSym(production,i));
		}
		
		return stillNullable;
	}
}