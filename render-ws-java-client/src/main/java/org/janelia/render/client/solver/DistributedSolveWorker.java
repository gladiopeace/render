package org.janelia.render.client.solver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import org.janelia.alignment.match.CanvasMatchResult;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.RenderDataClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ij.ImageJ;
import ij.ImagePlus;
import mpicbg.models.Affine2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.ErrorStatistic;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.Model;
import mpicbg.models.NoninvertibleModelException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.RigidModel2D;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
import mpicbg.models.TileUtil;
import mpicbg.models.TranslationModel2D;
import net.imglib2.RandomAccess;
import net.imglib2.algorithm.gauss3.Gauss3;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;
import script.imglib.algorithm.MedianFilter;

public class DistributedSolveWorker< G extends Model< G > & Affine2D< G >, B extends Model< B > & Affine2D< B >, S extends Model< S > & Affine2D< S > >
{
	public static double defaultLambda = 0.1;

	// attempts to stitch each section first (if the tiles are connected) and
	// then treat them as one big, "grouped" tile in the global optimization
	// the advantage is that potential deformations do not propagate into the individual
	// sections, but can be solved easily later using non-rigid alignment.
	final boolean stitchFirst;

//	final public static int numThreads = 1;
//
//	final public static double maxAllowedError = 10;
//	final public static int numIterations = 500;
//	final public static int maxPlateauWidth = 50;

	final protected static int visualizeZSection = 0;//10000;

	final RenderDataClient renderDataClient;
	final RenderDataClient matchDataClient;
	final String stack;

	final List< Pair< String, Double > > pGroupList;
	final Map<String, ArrayList<Double>> sectionIdToZMap;

	final int numThreads;

	final double maxAllowedErrorStitching;
	final int maxIterationsStitching, maxPlateauWidthStitching;

	final List<Double> blockOptimizerLambdasRigid, blockOptimizerLambdasTranslation;
	final List<Integer> blockOptimizerIterations, blockMaxPlateauWidth;
	final double blockMaxAllowedError;

	final SolveItem< G, B, S > inputSolveItem;
	final ArrayList< SolveItem< G, B, S > > solveItems;

	public DistributedSolveWorker(
			final SolveItemData< G, B, S > solveItemData,
			final List< Pair< String, Double > > pGroupList,
			final Map<String, ArrayList<Double>> sectionIdToZMap,
			final String baseDataUrl,
			final String owner,
			final String project,
			final String matchOwner,
			final String matchCollection,
			final String stack,
			final double maxAllowedErrorStitching,
			final int maxIterationsStitching,
			final int maxPlateauWidthStitching,
			final List<Double> blockOptimizerLambdasRigid,
			final List<Double> blockOptimizerLambdasTranslation,
			final List<Integer> blockOptimizerIterations,
			final List<Integer> blockMaxPlateauWidth,
			final double blockMaxAllowedError,
			final int numThreads )
	{
		this.renderDataClient = new RenderDataClient( baseDataUrl, owner, project );
		this.matchDataClient = new RenderDataClient( baseDataUrl, matchOwner, matchCollection );
		this.stack = stack;
		this.inputSolveItem = new SolveItem<>( solveItemData );
		this.pGroupList = pGroupList;
		this.sectionIdToZMap = sectionIdToZMap;

		this.blockOptimizerLambdasRigid = blockOptimizerLambdasRigid;
		this.blockOptimizerLambdasTranslation = blockOptimizerLambdasTranslation;
		this.blockOptimizerIterations = blockOptimizerIterations;
		this.blockMaxPlateauWidth = blockMaxPlateauWidth;

		this.maxAllowedErrorStitching = maxAllowedErrorStitching;
		this.maxIterationsStitching = maxIterationsStitching;
		this.maxPlateauWidthStitching = maxPlateauWidthStitching;
		this.blockMaxAllowedError = blockMaxAllowedError;

		this.stitchFirst = solveItemData.hasStitchingModel();
		this.numThreads = numThreads;

		this.solveItems = new ArrayList<>();
	}

	public List< SolveItemData< G, B, S > > getSolveItemDataList()
	{
		return solveItems.stream().map( SolveItem::getSolveItemData ).collect( Collectors.toList() );
	}

	protected void run() throws IOException, ExecutionException, InterruptedException, NoninvertibleModelException
	{
		assembleMatchData();
		split(); // splits

		for ( final SolveItem< G, B, S > solveItem : solveItems )
			solve( solveItem, numThreads );
	}

	protected void assembleMatchData() throws IOException
	{
		final Map<Double, ResolvedTileSpecCollection> zToTileSpecsMap = new HashMap<>();

		LOG.info( "block " + inputSolveItem.getId() + ": Loading transforms and matches from " + inputSolveItem.minZ() + " to layer " + inputSolveItem.maxZ() );

		// we store tile pairs and pointmatches here first, as we need to do stitching per section first if possible (if connected)
		final ArrayList< Pair< Pair< Tile< ? >, Tile< ? > >, List< PointMatch > > > pairs = new ArrayList<>();

		// maps from the z section to an entry in the above pairs list
		final HashMap< Integer, List< Integer > > zToPairs = new HashMap<>();

		for ( final Pair< String, Double > pGroupPair : pGroupList )
		{
			if ( pGroupPair.getB().doubleValue() < inputSolveItem.minZ() || pGroupPair.getB().doubleValue() > inputSolveItem.maxZ() )
				continue;

			final String pGroupId = pGroupPair.getA();

			LOG.info("block " + inputSolveItem.getId() + ": run: connecting tiles with pGroupId {}", pGroupId);

			final List<CanvasMatches> matches = matchDataClient.getMatchesWithPGroupId(pGroupId, false);

			for (final CanvasMatches match : matches)
			{
				final String pId = match.getpId();
				final TileSpec pTileSpec = SolveTools.getTileSpec(sectionIdToZMap, zToTileSpecsMap, renderDataClient, stack, pGroupId, pId);

				final String qGroupId = match.getqGroupId();
				final String qId = match.getqId();
				final TileSpec qTileSpec = SolveTools.getTileSpec(sectionIdToZMap, zToTileSpecsMap, renderDataClient, stack, qGroupId, qId);

				if ((pTileSpec == null) || (qTileSpec == null))
				{
					LOG.info("block " + inputSolveItem.getId() + ": run: ignoring pair ({}, {}) because one or both tiles are missing from stack {}", pId, qId, stack);
					continue;
				}

				// if any of the matches is outside the range we ignore them
				if ( pTileSpec.getZ() < inputSolveItem.minZ() || pTileSpec.getZ() > inputSolveItem.maxZ() || qTileSpec.getZ() < inputSolveItem.minZ() || qTileSpec.getZ() > inputSolveItem.maxZ() )
				{
					LOG.info("block " + inputSolveItem.getId() + ": run: ignoring pair ({}, {}) because it is out of range {}", pId, qId, stack);
					continue;
				}

				/*
				// TODO: REMOVE Artificial split of the data
				if ( pTileSpec.getZ().doubleValue() == qTileSpec.getZ().doubleValue() )
				{
					if ( pTileSpec.getZ().doubleValue() >= 10049 && pTileSpec.getZ().doubleValue() <= 10149 )
					{
						if ( ( pId.contains( "_0-0-1." ) && qId.contains( "_0-0-2." ) ) || ( qId.contains( "_0-0-1." ) && pId.contains( "_0-0-2." ) ) )
						{
							LOG.info("run: ignoring pair ({}, {}) to artificially split the data", pId, qId );
							continue;
						}
					}
				}
				*/

				final Tile< B > p, q;

				if ( !inputSolveItem.idToTileMap().containsKey( pId ) )
				{
					final Pair< Tile< B >, AffineModel2D > pairP = SolveTools.buildTileFromSpec( inputSolveItem.blockSolveModelInstance(), SolveItem.samplesPerDimension, pTileSpec);
					p = pairP.getA();
					inputSolveItem.idToTileMap().put( pId, p );
					inputSolveItem.idToPreviousModel().put( pId, pairP.getB() );
					inputSolveItem.idToTileSpec().put( pId, new MinimalTileSpec( pTileSpec ) );

					inputSolveItem.tileToIdMap().put( p, pId );
				}
				else
				{
					p = inputSolveItem.idToTileMap().get( pId );
				}

				if ( !inputSolveItem.idToTileMap().containsKey( qId ) )
				{
					final Pair< Tile< B >, AffineModel2D > pairQ = SolveTools.buildTileFromSpec( inputSolveItem.blockSolveModelInstance(), SolveItem.samplesPerDimension, qTileSpec);
					q = pairQ.getA();
					inputSolveItem.idToTileMap().put( qId, q );
					inputSolveItem.idToPreviousModel().put( qId, pairQ.getB() );
					inputSolveItem.idToTileSpec().put( qId, new MinimalTileSpec( qTileSpec ) );

					inputSolveItem.tileToIdMap().put( q, qId );
				}
				else
				{
					q = inputSolveItem.idToTileMap().get( qId );
				}

				// remember the entries, need to perform section-based stitching before running global optimization
				if ( stitchFirst )
					pairs.add( new ValuePair<>( new ValuePair<>( p, q ), CanvasMatchResult.convertMatchesToPointMatchList(match.getMatches()) ) );
				else
					p.connect(q, CanvasMatchResult.convertMatchesToPointMatchList(match.getMatches()));

				final int pZ = (int)Math.round( pTileSpec.getZ() );
				final int qZ = (int)Math.round( qTileSpec.getZ() );

				inputSolveItem.zToTileId().putIfAbsent( pZ, new HashSet<>() );
				inputSolveItem.zToTileId().putIfAbsent( qZ, new HashSet<>() );

				inputSolveItem.zToTileId().get( pZ ).add( pId );
				inputSolveItem.zToTileId().get( qZ ).add( qId );

				// use the following lambdas by default (potentially overwritten later)
				inputSolveItem.zToPreviousLambda().putIfAbsent( pZ, defaultLambda );
				inputSolveItem.zToPreviousLambda().putIfAbsent( qZ, defaultLambda );

				// if the pair is from the same layer we remember the current index in the pairs list
				if ( stitchFirst && pZ == qZ )
				{
					zToPairs.putIfAbsent( pZ, new ArrayList<>() );
					zToPairs.get( pZ ).add( pairs.size() - 1 );
				}
			}
		}

		if ( stitchFirst )
		{
			stitchSections(
					inputSolveItem,
					pairs,
					zToPairs,
					numThreads );
			
			// next, group the stitched tiles together
			for ( final Pair< Pair< Tile< ? >, Tile< ? > >, List< PointMatch > > pair : pairs )
			{
				final Tile< ? > p = inputSolveItem.tileToGroupedTile().get( pair.getA().getA() );
				final Tile< ? > q = inputSolveItem.tileToGroupedTile().get( pair.getA().getB() );

				if ( p == q ) // part of the same grouped tile
					continue;

				final String pTileId = inputSolveItem.tileToIdMap().get( pair.getA().getA() );
				final String qTileId = inputSolveItem.tileToIdMap().get( pair.getA().getB() );

				final AffineModel2D pModel = inputSolveItem.idToStitchingModel().get( pTileId );
				final AffineModel2D qModel = inputSolveItem.idToStitchingModel().get( qTileId );

				p.connect(q, SolveTools.createRelativePointMatches( pair.getB(), pModel, qModel ) );
			}
		}
		//else
		//	we are done
	}

	protected void stitchSections(
			final SolveItem< G,B,S > solveItem,
			final ArrayList< Pair< Pair< Tile< ? >, Tile< ? > >, List< PointMatch > > > pairs,
			final HashMap< Integer, List< Integer > > zToPairs,
			final int numThreads )
	{
		final S model = solveItem.stitchingSolveModelInstance();

		// combine tiles per layer that are be stitched first, but iterate over all z's 
		// (also those only consisting of single tiles, they are connected in z though)
		final ArrayList< Integer > zList = new ArrayList<>( solveItem.zToTileId().keySet() );
		Collections.sort( zList );

		for ( final int z : zList )
		{
			LOG.info( "block " + inputSolveItem.getId() + ": stitching z=" + z );

			final HashMap< String, Tile< S > > idTotile = new HashMap<>();
			final HashMap< Tile< S >, String > tileToId = new HashMap<>();

			// all connections within this z section
			if ( zToPairs.containsKey( z ) )
			{
				for ( final int index : zToPairs.get( z ) )
				{
					final Pair< Pair< Tile< ? >, Tile< ? > >, List< PointMatch > > pair = pairs.get( index );
					
					final String pId = solveItem.tileToIdMap().get( pair.getA().getA() );
					final String qId = solveItem.tileToIdMap().get( pair.getA().getB() );
	
					//LOG.info( "pId=" + pId  + " (" + idTotile.containsKey( pId ) + ") " + " qId=" + qId + " (" + idTotile.containsKey( qId ) + ") " + idTotile.keySet().size() );
	
					final Tile< S > p, q;
	
					if ( !idTotile.containsKey( pId ) )
					{
						//p = new Tile<>( model.copy() );
						// since we do preAlign later this seems redundant. However, it makes sure the tiles are more or less at the right global coordinates
						p = SolveTools.buildTile( solveItem.idToPreviousModel().get( pId ), model.copy(), 100, 100, 3 );
						idTotile.put( pId, p );
						tileToId.put( p, pId );
					}
					else
					{
						p = idTotile.get( pId );
					}
	
					if ( !idTotile.containsKey( qId ) )
					{
						//q = new Tile<>( model.copy() );
						q = SolveTools.buildTile( solveItem.idToPreviousModel().get( qId ), model.copy(), 100, 100, 3 );
						idTotile.put( qId, q );
						tileToId.put( q, qId );
					}
					else
					{
						q = idTotile.get( qId );
					}
	
					// TODO: do we really need to duplicate the PointMatches?
					p.connect( q, SolveTools.duplicate( pair.getB() ) );
				}
			}

			// add all missing TileIds as unconnected Tiles
			for ( final String tileId : solveItem.zToTileId().get( z ) )
				if ( !idTotile.containsKey( tileId ) )
				{
					LOG.info( "block " + inputSolveItem.getId() + ": unconnected tileId " + tileId );

					final Tile< S > tile = new Tile< S >( model.copy() );
					idTotile.put( tileId, tile );
					tileToId.put( tile, tileId );
				}

			// Now identify connected graphs within all tiles
			final ArrayList< Set< Tile< ? > > > sets = Tile.identifyConnectedGraphs( idTotile.values() );

			LOG.info( "block " + inputSolveItem.getId() + ": stitching z=" + z + " #sets=" + sets.size() );

			// solve each set (if size > 1)
			int setCount = 0;
			for ( final Set< Tile< ? > > set : sets ) // TODO: type sets correctly
			{
				LOG.info( "block " + inputSolveItem.getId() + ": Set=" + setCount++ );

				// the grouped tile for this set
				final Tile< B > groupedTile = new Tile<>( inputSolveItem.blockSolveModelInstance() );

				if ( set.size() > 1 )
				{
					final TileConfiguration tileConfig = new TileConfiguration();
					tileConfig.addTiles( set );

					// we always prealign (not sure how far off the current alignment in renderer is)
					// a simple preAlign suffices for Translation and Rigid as it doesn't matter which Tile is fixed during alignment
					try
					{
						tileConfig.preAlign();
					}
					catch ( NotEnoughDataPointsException | IllDefinedDataPointsException e )
					{
						LOG.info( "block " + inputSolveItem.getId() + ": Could not solve prealign for z=" + z + ", cause: " + e );
						e.printStackTrace();
					}

					// test if the graph has cycles, if yes we would need to do a solve
					if ( !( ( TranslationModel2D.class.isInstance( model ) || RigidModel2D.class.isInstance( model ) ) && !new Graph( new ArrayList<>( set ) ).isCyclic() ) )
					{
						LOG.info( "block " + inputSolveItem.getId() + ": Full solve required for stitching z=" + z  );

						try
						{
							TileUtil.optimizeConcurrently(
								new ErrorStatistic( maxPlateauWidthStitching + 1 ),
								maxAllowedErrorStitching,
								maxIterationsStitching,
								maxPlateauWidthStitching,
								1.0,
								tileConfig,
								tileConfig.getTiles(),
								tileConfig.getFixedTiles(),
								numThreads );

							LOG.info( "block " + inputSolveItem.getId() + ": Solve z=" + z + " avg=" + tileConfig.getError() + ", min=" + tileConfig.getMinError() + ", max=" + tileConfig.getMaxError() );
						}
						catch ( Exception e )
						{
							LOG.info( "block " + inputSolveItem.getId() + ": Could not solve stitiching for z=" + z + ", cause: " + e );
							e.printStackTrace();
						}
					}

					// save Tile transformations accordingly
					for ( final Tile< ? > t : set )
					{
						final String tileId = tileToId.get( t );
						final AffineModel2D affine = SolveTools.createAffine( ((Affine2D<?>)t.getModel()) );

						solveItem.idToStitchingModel().put( tileId, affine );

						// assign the original tile (we made a new one for stitching with a different model) to its grouped tile
						solveItem.tileToGroupedTile().put( solveItem.idToTileMap().get( tileId ), groupedTile );
						
						solveItem.groupedTileToTiles().putIfAbsent( groupedTile, new ArrayList<>() );
						solveItem.groupedTileToTiles().get( groupedTile ).add( solveItem.idToTileMap().get( tileId ) );

						LOG.info( "block " + inputSolveItem.getId() + ": TileId " + tileId + " Model=" + affine );
					}

					// Hack: show a section after alignment
					if ( visualizeZSection == z )
					{
						try
						{
							final HashMap< String, AffineModel2D > models = new HashMap<>();
							for ( final Tile< ? > t : set )
							{
								final String tileId = tileToId.get( t );
								models.put( tileId, solveItem.idToStitchingModel().get( tileId ) );
							}

							new ImageJ();
							ImagePlus imp1 = VisualizeTools.render( models, solveItem.idToTileSpec(), 0.15 );
							imp1.setTitle( "z=" + z );
						}
						catch ( NoninvertibleModelException e )
						{
							e.printStackTrace();
						}
					}

					//System.exit( 0 );
				}
				else
				{
					final String tileId = tileToId.get( set.iterator().next() );
					solveItem.idToStitchingModel().put( tileId, solveItem.idToPreviousModel().get( tileId ).copy() );

					// assign the original tile (we made a new one for stitching with a different model) to its grouped tile
					solveItem.tileToGroupedTile().put( solveItem.idToTileMap().get( tileId ), groupedTile );

					solveItem.groupedTileToTiles().putIfAbsent( groupedTile, new ArrayList<>() );
					solveItem.groupedTileToTiles().get( groupedTile ).add( solveItem.idToTileMap().get( tileId ) );

					LOG.info( "block " + inputSolveItem.getId() + ": Single TileId " + tileId );
				}
			}
		}
	}

	protected void split()
	{
		// the connectivity is defined by either idToTileMap().values() or tileToGroupedTile().values()
		final ArrayList< Set< Tile< ? > > > graphs;

		if ( stitchFirst )
			graphs = Tile.identifyConnectedGraphs( inputSolveItem.tileToGroupedTile().values() );
		else
			graphs = Tile.identifyConnectedGraphs( inputSolveItem.idToTileMap().values() );

		LOG.info( "block " + inputSolveItem.getId() + ": Graph of SolveItem " + inputSolveItem.getId() + " consists of " + graphs.size() + " subgraphs." );

		if ( graphs.size() == 1 )
		{
			solveItems.add( inputSolveItem );

			LOG.info( "block " + inputSolveItem.getId() + ": Graph 0 has " + graphs.get( 0 ).size() + " tiles." );
		}
		else
		{
			int graphCount = 0;

			for ( final Set< Tile< ? > > subgraph : graphs ) // TODO: type sets properly
			{
				LOG.info( "block " + inputSolveItem.getId() + ": new graph " + graphCount++ + " has " + subgraph.size() + " tiles." );

				int newMin = inputSolveItem.maxZ();
				int newMax = inputSolveItem.minZ();

				// first figure out new minZ and maxZ
				for ( final Tile< ? > potentiallyGroupedTile : subgraph )
				{
					final ArrayList< Tile< ? > > tiles = new ArrayList<>();

					if ( stitchFirst )
						tiles.addAll( inputSolveItem.groupedTileToTiles().get( potentiallyGroupedTile ) );
					else
						tiles.add( potentiallyGroupedTile );
					
					for ( final Tile< ? > t : tiles )
					{
						final MinimalTileSpec tileSpec = inputSolveItem.idToTileSpec().get( inputSolveItem.tileToIdMap().get( t ) );
	
						newMin = Math.min( newMin, (int)Math.round( tileSpec.getZ() ) );
						newMax = Math.max( newMax, (int)Math.round( tileSpec.getZ() ) );
					}
				}

				final SolveItem< G,B,S > solveItem = new SolveItem<>(
						new SolveItemData< G, B, S >(
							inputSolveItem.globalSolveModelInstance(),
							inputSolveItem.blockSolveModelInstance(),
							inputSolveItem.stitchingSolveModelInstance(),
							newMin,
							newMax ) );

				LOG.info( "block " + solveItem.getId() + ": old graph id=" + inputSolveItem.getId() + ", new graph id=" + solveItem.getId() );
				LOG.info( "block " + solveItem.getId() + ": min: " + newMin + " > max: " + newMax );

				// update all the maps
				for ( final Tile< ? > potentiallyGroupedTile : subgraph )
				{
					final ArrayList< Tile< B > > tiles = new ArrayList<>();

					if ( stitchFirst )
						tiles.addAll( inputSolveItem.groupedTileToTiles().get( potentiallyGroupedTile ) );
					else
						tiles.add( (Tile<B>)potentiallyGroupedTile );
					
					for ( final Tile< B > t : tiles )
					{
						final String tileId = inputSolveItem.tileToIdMap().get( t );
		
						solveItem.idToTileMap().put( tileId, t );
						solveItem.tileToIdMap().put( t, tileId );
						solveItem.idToPreviousModel().put( tileId, inputSolveItem.idToPreviousModel().get( tileId ) );
						solveItem.idToTileSpec().put( tileId, inputSolveItem.idToTileSpec().get( tileId ) );
						solveItem.idToNewModel().put( tileId, inputSolveItem.idToNewModel().get( tileId ) );
		
						if ( stitchFirst )
						{
							solveItem.idToStitchingModel().put( tileId, inputSolveItem.idToStitchingModel().get( tileId ) );
	
							final Tile< B > groupedTile = inputSolveItem.tileToGroupedTile().get( t );
	
							solveItem.tileToGroupedTile().put( t, groupedTile );
							solveItem.groupedTileToTiles().putIfAbsent( groupedTile, inputSolveItem.groupedTileToTiles().get( groupedTile ) );
						}
					}
				}

				// used for global solve outside
				for ( int z = solveItem.minZ(); z <= solveItem.maxZ(); ++z )
				{
					final HashSet< String > allTilesPerZ = inputSolveItem.zToTileId().get( z );

					if ( allTilesPerZ == null )
						continue;

					final HashSet< String > myTilesPerZ = new HashSet<>();

					for ( final String tileId : allTilesPerZ )
					{
						if ( solveItem.idToTileMap().containsKey( tileId ) )
							myTilesPerZ.add( tileId );
					}
					
					if ( myTilesPerZ.size() == 0 )
					{
						LOG.info( "block " + solveItem.getId() + ": ERROR: z=" + z + " of new graph has 0 tileIds, the must not happen, this is a bug." );
						System.exit( 0 );
					}

					solveItem.zToTileId().put( z, myTilesPerZ );
					solveItem.zToPreviousLambda().putIfAbsent( z, inputSolveItem.zToPreviousLambda().get( z ) );
				}

				solveItems.add( solveItem );
				// cannot update overlapping items here due to multithreading and the fact that the other solveitems are also being split up
			}
		}
		//System.exit( 0 );
	}

	protected static double[] computeErrors( final Collection< ? extends Tile< ? > > tiles )
	{
		double cd = 0.0;
		double minError = Double.MAX_VALUE;
		double maxError = 0.0;
		for ( final Tile< ? > t : tiles )
		{
			t.update();
			final double d = t.getDistance();
			if ( d < minError ) minError = d;
			if ( d > maxError ) maxError = d;
			cd += d;
		}
		cd /= tiles.size();
		
		return new double[] { minError, cd, maxError };
	}

	protected void solve(
			final SolveItem< G,B,S > solveItem,
			final int numThreads
			) throws InterruptedException, ExecutionException
	{
		final TileConfiguration tileConfig = new TileConfiguration();

		if ( stitchFirst )
		{
			tileConfig.addTiles(solveItem.tileToGroupedTile().values());
			LOG.info("block " + solveItem.getId() + ": run: optimizing {} tiles", solveItem.groupedTileToTiles().keySet().size() );
		}
		else
		{
			tileConfig.addTiles(solveItem.idToTileMap().values());
			LOG.info("block " + solveItem.getId() + ": run: optimizing {} tiles", solveItem.idToTileMap().size() );
		}

		fakePreAlign( new ArrayList<>( tileConfig.getTiles() ), solveItem );

		LOG.info( "block " + solveItem.getId() + ": prealigning with translation only" );

		for (final Tile< ? > tile : tileConfig.getTiles() )
			((InterpolatedAffineModel2D) tile.getModel()).setLambda( 1.0 ); // all translation
		
		try
		{
			double[] errors = computeErrors( tileConfig.getTiles() );
			LOG.info( "errors: " + errors[ 0 ] + "/" + errors[ 1 ] + "/" + errors[ 2 ] );
			
			tileConfig.preAlign();
			
			errors = computeErrors( tileConfig.getTiles() );
			LOG.info( "errors: " + errors[ 0 ] + "/" + errors[ 1 ] + "/" + errors[ 2 ] );
		}
		catch (NotEnoughDataPointsException | IllDefinedDataPointsException e)
		{
			LOG.info( "block " + solveItem.getId() + ": prealign failed: " + e );
			e.printStackTrace();
		}

		for (final Tile< ? > tile : tileConfig.getTiles() )
			((InterpolatedAffineModel2D) tile.getModel()).setLambda( 0.0 ); // all affine/rigid

		LOG.info( "block " + solveItem.getId() + ": lambda's used (rigid, translation):" );

		for ( int l = 0; l < blockOptimizerLambdasRigid.size(); ++l )
		{
			LOG.info( "block " + solveItem.getId() + ": l=" + blockOptimizerLambdasRigid.get( l ) + ", " + blockOptimizerLambdasTranslation.get( l ) );
		}

		/*
		for ( int s = 0; s < blockOptimizerLambdasRigid.size(); ++s )
		{
			final double lambdaRigid = blockOptimizerLambdasRigid.get( s );
			final double lambdaTranslation = blockOptimizerLambdasTranslation.get( s );

			for (final Tile< ? > tile : tileConfig.getTiles() )
			{
				((InterpolatedAffineModel2D)((InterpolatedAffineModel2D) tile.getModel()).getA()).setLambda(lambdaRigid);
				((InterpolatedAffineModel2D) tile.getModel()).setLambda(lambdaTranslation);
			}

			
			int numIterations = blockOptimizerIterations.get( s );

			final int maxPlateauWidth = blockMaxPlateauWidth.get( s );

			LOG.info( "block " + solveItem.getId() + ": l(rigid)=" + lambdaRigid + ", l(translation)=" + lambdaTranslation + ", numIterations=" + numIterations + ", maxPlateauWidth=" + maxPlateauWidth );

			final ErrorStatistic observer = new ErrorStatistic( maxPlateauWidth + 1 );
			final float damp = 1.0f;
			TileUtil.optimizeConcurrently(
					observer,
					blockMaxAllowedError,
					numIterations,
					maxPlateauWidth,
					damp,
					tileConfig,
					tileConfig.getTiles(),
					tileConfig.getFixedTiles(),
					numThreads );
		}
		*/

		//
		// create lookup for the new models
		//
		solveItem.idToNewModel().clear();

		if ( stitchFirst )
		{
			computeMetaDataLambdas( solveItem );
			
			final ArrayList< String > tileIds = new ArrayList<>();
			final HashMap< String, AffineModel2D > tileIdToGroupModel = new HashMap<>();

			for ( final Tile< ? > tile : solveItem.tileToGroupedTile().keySet() )
			{
				final String tileId = solveItem.tileToIdMap().get( tile );

				tileIds.add( tileId );
				tileIdToGroupModel.put( tileId, SolveTools.createAffine( (Affine2D<?>)solveItem.tileToGroupedTile().get( tile ).getModel() ) );
			}

			Collections.sort( tileIds );

			for (final String tileId : tileIds )
			{
				final AffineModel2D affine = solveItem.idToStitchingModel().get( tileId ).copy();

				affine.preConcatenate( tileIdToGroupModel.get( tileId ) );

				/*
				// TODO: REMOVE
				if ( inputSolveItem.getId() == 2 )
				{
				final TranslationModel2D t = new TranslationModel2D();
				t.set( 1000, 0 );
				affine.preConcatenate( t );
				}
				*/
	
				solveItem.idToNewModel().put( tileId, affine );
				LOG.info("block " + solveItem.getId() + ": tile {} model from grouped tile is {}", tileId, affine);
			}

		}
		else
		{
			final ArrayList< String > tileIds = new ArrayList<>( solveItem.idToTileMap().keySet() );
			Collections.sort( tileIds );
	
			for (final String tileId : tileIds )
			{
				final Tile< ? > tile = solveItem.idToTileMap().get(tileId);
				final AffineModel2D affine = SolveTools.createAffine( (Affine2D<?>)tile.getModel() );
	
				/*
				// TODO: REMOVE
				if ( inputSolveItem.getId() == 2 )
				{
				final TranslationModel2D t = new TranslationModel2D();
				t.set( 1000, 0 );
				affine.preConcatenate( t );
				}
				*/
	
				solveItem.idToNewModel().put( tileId, affine );
				LOG.info("block " + solveItem.getId() + ": tile {} model is {}", tileId, affine);
			}
		}
	}

	protected static void fakePreAlign( final List< Tile< ? > > tiles, final SolveItem<?, ?, ?> solveItem )
	{
		LOG.info( "FakeAlign" );
		
		final HashMap< Tile< ? >, Tile< TranslationModel2D > > tilesToFaketiles = new HashMap<>();
		final HashMap< Point, Tile< ? > > p1ToTile = new HashMap<>(); // to efficiently find a tile associated with a pointmatch

		for ( final Tile< ? > tile : tiles )
		{
			final Tile< TranslationModel2D > fakeTile = new Tile<>( new TranslationModel2D() );
			tilesToFaketiles.put( tile, fakeTile );

			for ( final PointMatch pm : tile.getMatches() )
			{
				if ( p1ToTile.containsKey( pm.getP1() ) )
					System.out.println( "Collision! " + p1ToTile.get( pm.getP1() ) );

				p1ToTile.put( pm.getP1(), tile );
			}
		}

		final HashSet< Tile<?> > alreadyVisited = new HashSet<>();

		for ( final Tile< ? > tile : tiles )
		{
			LOG.info( "tile " + tile + " (" + tile.getMatches().size() + " matches). " + solveItem.idToTileSpec().get( solveItem.tileToIdMap().get( solveItem.groupedTileToTiles().get( tile ).get( 0 ) ) ).getZ() );
			
			final HashMap< Tile< TranslationModel2D >, ArrayList< PointMatch > > matches = new HashMap<>();

			for ( final PointMatch pm : tile.getMatches() )
			{
				final Tile< ? > connectedTile = p1ToTile.get( pm.getP2() );
				
				if ( alreadyVisited.contains( connectedTile ) )
					continue;

				final Tile< TranslationModel2D > connectedFakeTile = tilesToFaketiles.get( connectedTile );

				final PointMatch newPM = new PointMatch(
						new Point( pm.getP1().getL().clone(), pm.getP1().getW().clone() ),
						new Point( pm.getP2().getL().clone(), pm.getP2().getW().clone() ),
						pm.getWeight() );
				
				matches.putIfAbsent( connectedFakeTile, new ArrayList<PointMatch>() );
				matches.get( connectedFakeTile ).add( newPM );
			}
		
			final Tile< TranslationModel2D > fakeTile = tilesToFaketiles.get( tile );

			for ( final Tile< TranslationModel2D > connectedFakeTile : matches.keySet() )
			{
				final ArrayList< PointMatch > newMatches = matches.get( connectedFakeTile ); 
				fakeTile.connect( connectedFakeTile, newMatches );
			}
			
			alreadyVisited.add( tile );
			LOG.info( "fakeTile " + fakeTile + " (" + fakeTile.getMatches().size() + " matches)." );
		}

		final TileConfiguration tileConfig = new TileConfiguration();
		tileConfig.addTiles( tilesToFaketiles.values() );

		try
		{
			double[] errors = computeErrors( tileConfig.getTiles() );
			LOG.info( "errors: " + errors[ 0 ] + "/" + errors[ 1 ] + "/" + errors[ 2 ] );
			
			tileConfig.preAlign();
			
			errors = computeErrors( tileConfig.getTiles() );
			LOG.info( "errors: " + errors[ 0 ] + "/" + errors[ 1 ] + "/" + errors[ 2 ] );
		}
		catch (NotEnoughDataPointsException | IllDefinedDataPointsException e)
		{
			LOG.info( "prealign failed: " + e );
			e.printStackTrace();
		}
	}

	protected static void computeMetaDataLambdas( final SolveItem< ?,?,? > solveItem )
	{
		// test
		final HashMap< Integer, Tile< ? > > zToGroupedTile = new HashMap<>();

		for ( final Tile< ? > groupedTile : solveItem.groupedTileToTiles().keySet() )
		{
			final Tile< ? > aTile = solveItem.groupedTileToTiles().get( groupedTile ).get( 0 ); 
			final String tileId = solveItem.tileToIdMap().get( aTile );
			final int z = (int)Math.round( solveItem.idToTileSpec().get( tileId ).getZ() );
			zToGroupedTile.put( z, groupedTile );
		}

		final ArrayList< Integer > allZ = new ArrayList<Integer>( zToGroupedTile.keySet() );
		Collections.sort( allZ );

		final Img< DoubleType > valueX = ArrayImgs.doubles( allZ.size() );
		final Img< DoubleType > valueY = ArrayImgs.doubles( allZ.size() );

		RandomAccess< DoubleType > rX = valueX.randomAccess();
		RandomAccess< DoubleType > rY = valueY.randomAccess();
		
		for ( int deltaZ = 0; deltaZ < allZ.size(); ++ deltaZ )
		{
			double[] offset = layerMinBounds( allZ.get( deltaZ ), zToGroupedTile, solveItem);
			
			rX.setPosition( new int[] { deltaZ } );
			rY.setPosition( new int[] { deltaZ } );

			rX.get().set( offset[ 0 ] );
			rY.get().set( offset[ 1 ] );
		}

		RandomAccess< DoubleType > rxIn = Views.extendMirrorSingle( valueX ).randomAccess();
		RandomAccess< DoubleType > ryIn = Views.extendMirrorSingle( valueY ).randomAccess();

		final Img< DoubleType > derX = ArrayImgs.doubles( allZ.size() );
		final Img< DoubleType > derY = ArrayImgs.doubles( allZ.size() );

		RandomAccess< DoubleType > rxOut = derX.randomAccess();
		RandomAccess< DoubleType > ryOut = derY.randomAccess();

		for ( int deltaZ = 0; deltaZ < allZ.size(); ++ deltaZ )
		{
			rxIn.setPosition( new int[] { deltaZ - 1 } );
			ryIn.setPosition( new int[] { deltaZ - 1 } );

			double x = rxIn.get().get();
			double y = ryIn.get().get();
			
			rxIn.fwd( 0 );
			ryIn.fwd( 0 );
			
			rxOut.setPosition( rxIn );
			ryOut.setPosition( ryIn );

			rxOut.get().set( Math.pow( x - rxIn.get().get(), 2 ) );
			ryOut.get().set( Math.pow( y - ryIn.get().get(), 2 ) );

			LOG.info( "Z: " + allZ.get( deltaZ ) + " - " + rxOut.get().get() + " " + ryOut.get().get() );
		}

		final Img< DoubleType > filterX = ArrayImgs.doubles( allZ.size() );
		final Img< DoubleType > filterY = ArrayImgs.doubles( allZ.size() );

		Gauss3.gauss( 20, Views.extendMirrorSingle( derX ), filterX );
		Gauss3.gauss( 20, Views.extendMirrorSingle( derY ), filterY );

		rX = filterX.randomAccess();
		rY = filterY.randomAccess();
		
		for ( int i = 0; i < allZ.size(); ++i )
		{
			rX.setPosition(new int[] { i } );
			rY.setPosition(new int[] { i } );
		
			double lambda = (rX.get().get() + rY.get().get() );

			rY.get().set( lambda );

			lambda = Math.max( 0, lambda < 115 ? ( 0.000023333*lambda*lambda - 0.005233333*lambda + 0.3 ) / 2.0 : 0.00674563 / 2.0 );

			rX.get().set( lambda );
		}

		Gauss3.gauss( 5, Views.extendMirrorSingle( filterX ), filterX );

		for ( int i = 0; i < allZ.size(); ++i )
		{
			rX.setPosition(new int[] { i } );

			solveItem.zToPreviousLambda().put( allZ.get( i ), rX.get().get() );
		}

		new ImageJ();

		ImageJFunctions.show( filterX );
		ImageJFunctions.show( filterY );
		SimpleMultiThreading.threadHaltUnClean();

		// test end
	}


	protected static double[] layerMinBounds( final int z, final HashMap< Integer, Tile< ? > > zToGroupedTile, final SolveItem< ?,?,? > solveItem )
	{
		final Tile< ? > groupedTile = zToGroupedTile.get( z );
		
		final AffineModel2D groupedModel = SolveTools.createAffine( (Affine2D<?>)groupedTile.getModel() );
		
		double minX = Double.MAX_VALUE;
		double minY = Double.MAX_VALUE;

		for ( final Tile< ? > tile : solveItem.groupedTileToTiles().get( groupedTile ) )
		{
			final String tileId = solveItem.tileToIdMap().get( tile );
			final MinimalTileSpec tileSpec = solveItem.idToTileSpec().get( tileId );

			final AffineModel2D affine = solveItem.idToStitchingModel().get( tileId ).copy();
			affine.preConcatenate( groupedModel );

			double[] tmp = new double[ 2 ];
			
			tmp[ 0 ] = 0;
			tmp[ 1 ] = tileSpec.getHeight() / 2.0;

			affine.applyInPlace( tmp );
			
			minX = Math.min( minX, tmp[ 0 ] );
			minY = Math.min( minY, tmp[ 1 ] );
			

			tmp[ 0 ] = tileSpec.getWidth() / 2;
			tmp[ 1 ] = 0;

			affine.applyInPlace( tmp );
			
			minX = Math.min( minX, tmp[ 0 ] );
			minY = Math.min( minY, tmp[ 1 ] );

		}
		
		return new double[] { minX, minY };
	}

	private static final Logger LOG = LoggerFactory.getLogger(DistributedSolveWorker.class);
}
