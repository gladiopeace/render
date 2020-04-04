package org.janelia.render.client.solver;

import java.util.ArrayList;
import java.util.List;

public class SolveSet
{
	final List< SolveItem > leftItems;
	final List< SolveItem > rightItems;

	public SolveSet( final List< SolveItem > leftItems, final List< SolveItem > rightItems )
	{
		this.leftItems = leftItems;
		this.rightItems = rightItems;
	}

	public List< SolveItem > allItems()
	{
		final ArrayList< SolveItem > all = new ArrayList<>();
		all.addAll( leftItems );
		all.addAll( rightItems );

		return all;
	}

	@Override
	public String toString()
	{
		final int numSetsLeft = leftItems.size();

		String out = "";

		for ( int i = 0; i < numSetsLeft; ++i )
		{
			out += leftItems.get( i ).getId() + ": " + leftItems.get( i ).minZ() + " >> " + leftItems.get( i ).maxZ();

			if ( i < numSetsLeft - 1 )
				out += "\n\t" + rightItems.get( i ).getId() + ": " + rightItems.get( i ).minZ() + " >> " + rightItems.get( i ).maxZ() + "\n";
		}

		return out;
	}
}
