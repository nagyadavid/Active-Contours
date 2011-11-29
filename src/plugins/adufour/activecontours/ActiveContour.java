package plugins.adufour.activecontours;

import icy.canvas.IcyCanvas;
import icy.image.IcyBufferedImage;
import icy.roi.ROI;
import icy.roi.ROI2D;
import icy.roi.ROI2DArea;
import icy.roi.ROI2DEllipse;
import icy.roi.ROI2DPolygon;
import icy.roi.ROI2DRectangle;
import icy.roi.ROI2DShape;
import icy.sequence.Sequence;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.Iterator;

import javax.media.j3d.BoundingSphere;
import javax.vecmath.Point3d;
import javax.vecmath.Vector3d;

import plugins.adufour.ezplug.EzMessage;
import plugins.adufour.ezplug.EzMessage.MessageType;
import plugins.adufour.ezplug.EzMessage.OutputType;
import plugins.adufour.ezplug.EzVarDouble;
import plugins.adufour.ezplug.EzVarInteger;
import plugins.fab.trackmanager.Detection;

public class ActiveContour extends Detection
{
	
	private static final class Segment implements Iterable<Point3d>
	{
		final ArrayList<Point3d>	points;
		
		Segment(Point3d head, Point3d tail)
		{
			points = new ArrayList<Point3d>(2);
			points.add(head);
			points.add(tail);
		}
		
		final Point3d getHead()
		{
			return points.get(0);
		}
		
		final Point3d getTail()
		{
			return points.get(points.size() - 1);
		}
		
		final void addHead(Point3d p)
		{
			points.add(0, p);
		}
		
		final void addHead(Segment s)
		{
			for (int i = 0; i < s.points.size(); i++)
				points.add(i, s.points.get(i));
		}
		
		final void addTail(Point3d p)
		{
			points.add(p);
		}
		
		public Iterator<Point3d> iterator()
		{
			return points.iterator();
		}
	}
	
	final ActiveContours			owner;
	
	final ArrayList<Point3d>		points			= new ArrayList<Point3d>();
	
	Path2D.Double					path			= new Path2D.Double();
	
	SlidingWindow					convergence;
	
	private Vector3d[]				finalForces;
	
	private Vector3d[]				contourNormals;
	
	private boolean					normalsNeedUpdate;
	
	private Vector3d[]				feedbackForces;
	
	private final BoundingSphere	boundingSphere	= new BoundingSphere();
	
	private boolean					boundingSphereNeedsUpdate;
	
	private final EzVarDouble		contour_resolution;
	
	private final EzVarInteger		contour_minArea;
	
	private ActiveContour(ActiveContours owner, EzVarDouble contour_resolution, EzVarInteger contour_minArea, SlidingWindow convergenceWindow)
	{
		super(0, 0, 0, 0);
		
		this.owner = owner;
		this.contour_resolution = contour_resolution;
		this.contour_minArea = contour_minArea;
		this.convergence = convergenceWindow;
		
		color = new Color((float) Math.random(), (float) Math.random(), (float) Math.random());
	}
	
	/**
	 * Creates a clone of the specified contour
	 * 
	 * @param contour
	 */
	public ActiveContour(ActiveContour contour)
	{
		this(contour.owner, contour.contour_resolution, contour.contour_minArea, new SlidingWindow(contour.convergence.size));
		// TODO later on, clone the resolution variables as well if needed
		
		setX(contour.x);
		setY(contour.y);
		
		color = contour.color;
		points.ensureCapacity(contour.points.size());
		
		for (Point3d p : contour.points)
			addPoint(p);
		
		// in any case, don't forget to close the path
		path.closePath();
	}
	
	public ActiveContour(ActiveContours owner, EzVarDouble contour_resolution, EzVarInteger contour_minArea, SlidingWindow convergenceWindow, ROI2D roi) throws TopologyException
	{
		this(owner, contour_resolution, contour_minArea, convergenceWindow);
		
		if (roi == null)
		{
			EzMessage.message("Please draw or select a ROI", MessageType.ERROR, OutputType.DIALOG);
		}
		
		if (!(roi instanceof ROI2DEllipse) && !(roi instanceof ROI2DRectangle) && !(roi instanceof ROI2DPolygon) && !(roi instanceof ROI2DArea))
		{
			EzMessage.message("Wrong ROI type. Only Rectangle, Ellipse and Polygon are currently supported", MessageType.ERROR, OutputType.DIALOG);
		}
		
		if (roi instanceof ROI2DArea)
		{
			owner.input.getValue().addPainter(this);
			triangulate((ROI2DArea) roi, contour_resolution.getValue());
		}
		else
		{
			// convert the ROI into a linked list of points
			
			double[] segment = new double[6];
			
			PathIterator path = ((ROI2DShape) roi).getPathIterator(null, 0.1);
			
			// first segment is necessarily a "move to" operation
			
			path.currentSegment(segment);
			addPoint(new Point3d(segment[0], segment[1], 0));
			
			while (!path.isDone())
			{
				if (path.currentSegment(segment) == PathIterator.SEG_LINETO)
				{
					addPoint(new Point3d(segment[0], segment[1], 0));
				}
				path.next();
				
				// the final one should be a "close" operation, do nothing
			}
		}
		
		// in any case, don't forget to close the path
		path.closePath();
	}
	
	public void setConvergenceWindow(SlidingWindow window)
	{
		convergence = window;
	}
	
	private void triangulate(ROI2DArea roi, double resolution) throws TopologyException
	{
		ArrayList<Segment> segments = new ArrayList<Segment>();
		
		Rectangle bounds = roi.getBounds();
		
		int grid = 1;// Math.max(1, (int) Math.round(resolution));
		double halfgrid = 0.5 * grid;
		
		int cubeWidth = grid;
		int cubeHeight = grid * bounds.width;
		int cubeDiag = cubeWidth + cubeHeight;
		
		boolean[] mask = roi.getAsBooleanMask(roi.getBounds());
		// erase first line and first row to ensure closed contours
		java.util.Arrays.fill(mask, 0, bounds.width - 1, false);
		for (int o = 0; o < mask.length; o += bounds.width)
			mask[o] = false;
		
		for (int j = 0; j < bounds.height; j += grid)
			for (int i = 0, index = j * bounds.width; i < bounds.width; i += grid, index += grid)
			{
				// The image is divided into square cells containing two
				// triangles each:
				//
				// a---b---
				// |../|../
				// |./.|./.
				// |/..|/..
				// c---d---
				//
				// By convention I choose to turn around the object in a
				// clockwise fashion
				// Warning: to ensure connectivity, the objects must NOT touch
				// the image border, strange behavior may occur otherwise
				
				boolean a = mask[index];
				boolean b = (i + grid < bounds.width) && mask[index + cubeWidth];
				boolean c = (j + grid < bounds.height) && mask[index + cubeHeight];
				boolean d = (i + grid < bounds.width) && (j + grid < bounds.height) && mask[index + cubeDiag];
				
				// For each triangle, check for difference between image values
				// to determine the contour location
				// => there are 6 possible combinations in each triangle, that
				// is 12 per cube
				
				if (a != b)
				{
					if (b == c) // diagonal edge
					{
						if (a == false) // b,c are inside
						{
							createEdge(segments, i, j + 0.5, i + halfgrid, j);
							
						}
						else
						// b,c are outside
						{
							createEdge(segments, i + halfgrid, j, i, j + halfgrid);
							
						}
					}
					else
					// a = c -> vertical edge
					{
						if (a == false) // a,c are outside
						{
							createEdge(segments, i + halfgrid, j + halfgrid, i + halfgrid, j);
							
						}
						else
						// a,c are inside
						{
							createEdge(segments, i + halfgrid, j, i + halfgrid, j + halfgrid);
							
						}
					}
				}
				else // a = b -> horizontal edge only if c is different
				if (a != c)
				{
					if (a == false) // a,b are outside
					{
						createEdge(segments, i, j + halfgrid, i + halfgrid, j + halfgrid);
						
					}
					else
					// a,b are inside
					{
						createEdge(segments, i + halfgrid, j + halfgrid, i, j + halfgrid);
						
					}
				}
				
				if (c != d)
				{
					if (b == c) // diagonal edge
					{
						if (c == false) // b,c are outside
						{
							createEdge(segments, i + halfgrid, j + grid, i + grid, j + halfgrid);
							
						}
						else
						// b,c are inside
						{
							createEdge(segments, i + grid, j + halfgrid, i + halfgrid, j + grid);
							
						}
					}
					else
					// b = d -> vertical edge
					{
						if (c == false) // b,d are inside
						{
							createEdge(segments, i + halfgrid, j + grid, i + halfgrid, j + halfgrid);
							
						}
						else
						// b,d are outside
						{
							createEdge(segments, i + halfgrid, j + halfgrid, i + halfgrid, j + grid);
							
						}
					}
				}
				else // c = d -> horizontal edge only if b is different
				if (b != c)
				{
					if (b == false) // c,d are inside
					{
						createEdge(segments, i + halfgrid, j + halfgrid, i + grid, j + halfgrid);
						
					}
					else
					// c,d are outside
					{
						createEdge(segments, i + grid, j + halfgrid, i + halfgrid, j + halfgrid);
						
					}
				}
			}
		
		if (segments.size() == 0)
			return;
		
		for (Point3d p : segments.get(0))
		{
			p.x += bounds.x;
			p.y += bounds.y;
			addPoint(p);
		}
		
		// at this point the triangulated contour has an actual resolution of halfgrid
		// if 2*resolution < desired_resolution, resample() will loop and destroy the contour
		// decimate the contour by a factor 2 recursively until 2*resolution >= desired_resolution
		
		double current_resolution_doubled = halfgrid * 2;
		while (current_resolution_doubled < resolution * 0.7)
		{
			for (int i = 0; i < points.size(); i++)
				points.remove(i);
			current_resolution_doubled *= 2;
		}
		
		reSample(0.7, 1.4);
	}
	
	private static void createEdge(ArrayList<Segment> segments, double xStart, double yStart, double xEnd, double yEnd)
	{
		double EPSILON = 0.00001;
		
		Point3d head = new Point3d(xStart, yStart, 0);
		Point3d tail = new Point3d(xEnd, yEnd, 0);
		
		if (segments.size() == 0)
		{
			segments.add(new Segment(head, tail));
			return;
		}
		
		int insertAtTailOf = -1, insertAtHeadOf = -1;
		
		for (int i = 0; i < segments.size(); i++)
		{
			if (tail.distance(segments.get(i).getHead()) <= EPSILON)
				insertAtHeadOf = i;
			else if (head.distance(segments.get(i).getTail()) <= EPSILON)
				insertAtTailOf = i;
		}
		
		if (insertAtTailOf >= 0)
		{
			if (insertAtHeadOf >= 0)
			{
				segments.get(insertAtHeadOf).addHead(segments.get(insertAtTailOf));
				segments.remove(insertAtTailOf);
			}
			else
			{
				segments.get(insertAtTailOf).addTail(tail);
			}
		}
		else if (insertAtHeadOf >= 0)
		{
			segments.get(insertAtHeadOf).addHead(head);
		}
		else
		{
			segments.add(new Segment(head, tail));
		}
	}
	
	private void addPoint(Point3d p)
	{
		points.add(p);
		
		if (points.size() == 1)
		{
			// first point was added => start the path
			path.moveTo(p.x, p.y);
		}
		else
		{
			// draw the path using lines (for now)
			path.lineTo(p.x, p.y);
			// TODO use quad curves for more elegance
		}
	}
	
	/**
	 * Re-samples the Contour according to an 'average distance between points' criterion. This
	 * method ensures that the distance between two consecutive points is strictly comprised between
	 * a minimum value and a maximum value. In order to avoid oscillatory behavior, 'max' and 'min'
	 * should verify the following relations: min < 1, max > 1, 2*min <= max.
	 * 
	 * @param minFactor
	 *            the minimum distance between two points.
	 * @param maxFactor
	 *            the maximum distance between two points.
	 */
	public void reSample(double minFactor, double maxFactor) throws TopologyException
	{
		if (getDimension(2) < contour_minArea.getValue())
			throw new TopologyException(this, new ActiveContour[] {});
		
		double minLength = contour_resolution.getValue() * minFactor;
		double maxLength = contour_resolution.getValue() * maxFactor;
		
		ActiveContour[] children = checkForLoopOrDivision(contour_resolution.getValue(), contour_minArea.getValue());
		
		if (children != null)
			throw new TopologyException(this, children);
		
		// optimization to avoid multiple points.size() calls (WARNING: n must
		// be updated manually whenever points is changed)
		int n = points.size();
		
		boolean noChange = false;
		
		while (noChange == false)
		{
			noChange = true;
			
			// all points but the last
			for (int i = 0; i < n - 1; i++)
			{
				if (n < 4)
					return;
				
				Point3d pt1 = points.get(i);
				Point3d pt2 = points.get(i + 1);
				
				double distance = pt1.distance(pt2);
				
				if (distance < minLength)
				{
					noChange = false;
					pt2.set((pt1.x + pt2.x) * 0.5, (pt1.y + pt2.y) * 0.5, 0);
					points.remove(i);
					i--; // comes down to i-1+1 when looping
					n--;
				}
				else if (distance > maxLength)
				{
					noChange = false;
					
					points.add(i + 1, new Point3d((pt1.x + pt2.x) * 0.5, (pt1.y + pt2.y) * 0.5, 0));
					i++; // comes down to i+=2 when looping
					n++;
				}
			}
			
			// last point
			Point3d pt1 = points.get(n - 1);
			Point3d pt2 = points.get(0);
			
			if (pt1.distance(pt2) < minLength)
			{
				noChange = false;
				pt2.set((pt1.x + pt2.x) * 0.5, (pt1.y + pt2.y) * 0.5, 0);
				points.remove(n - 1);
				n--;
			}
			else if (pt1.distance(pt2) > maxLength)
			{
				noChange = false;
				points.add(new Point3d((pt1.x + pt2.x) * 0.5, (pt1.y + pt2.y) * 0.5, 0));
				n++;
			}
		}
		
		// re-sampling is done => update internal structures
		
		final int nbPoints = n;
		if (finalForces == null || finalForces.length != nbPoints)
		{
			finalForces = new Vector3d[nbPoints];
			contourNormals = new Vector3d[nbPoints];
			feedbackForces = new Vector3d[nbPoints];
			
			path.reset();
			
			for (int i = 0; i < nbPoints; i++)
			{
				finalForces[i] = new Vector3d();
				contourNormals[i] = new Vector3d();
				feedbackForces[i] = new Vector3d();
				Point3d p = points.get(i);
				
				if (i == 0)
				{
					path.moveTo(p.x, p.y);
				}
				else
				{
					path.lineTo(p.x, p.y);
				}
			}
			
			path.closePath();
		}
		
		normalsNeedUpdate = true;
		boundingSphereNeedsUpdate = true;
	}
	
	/**
	 * Checks whether the contour is self-intersecting. Depending on the given parameters, a
	 * self-intersection can be considered as a loop or as a contour division.
	 * 
	 * @param minSpacing
	 *            the distance threshold between non-neighboring points to detect self-intersection
	 * @param minArea
	 *            if a self-intersection is detected, this value specifies if the new contours are
	 *            kept or eliminated
	 * @return null if either no self-intersection is detected or if one of the new contours is too
	 *         small, or an array of Contour2Ds with 0 elements if both contours are too small, and
	 *         2 elements if both contours are viable
	 */
	private ActiveContour[] checkForLoopOrDivision(double minSpacing, double minArea)
	{
		int i = 0, j = 0, end, n = points.size();
		
		boolean division = false;
		
		for (i = 0; i < n; i++)
		{
			Point3d pt = points.get(i);
			
			for (j = i + 2; j < n - 1; j++)
			{
				division = (pt.distance(points.get(j)) < minSpacing);
				
				if (division && (j == i + 2))
				{
					points.remove(i + 1);
					n--;
					division = false;
				}
				
				if (division)
					break;
			}
			if (division)
				break;
		}
		
		if (!division)
			return null;
		
		end = j - i;
		ActiveContour c1 = new ActiveContour(this.owner, contour_resolution, contour_minArea, new SlidingWindow(this.convergence.size));
		c1.setT(getT());
		
		for (int p = 0; p < end; p++)
			c1.addPoint(points.get(p + i));
		
		end = i + n - j;
		ActiveContour c2 = new ActiveContour(this.owner, contour_resolution, contour_minArea, new SlidingWindow(this.convergence.size));
		c2.setT(getT());
		
		for (int p = 0, pj = p + j; p < end; p++, pj++)
			c2.addPoint(points.get(pj < n ? pj : pj - n));
		
		double c1area = c1.getDimension(2), c2area = c2.getDimension(2);
		
		// if only one of the two new contours has a size lower than minArea, then the division
		// should be considered as a simple loop, thus the remaining contour should inherit the
		// trackID of the parent contour
		
		if (c1area > minArea)
		{
			if (c2area > minArea)
				return new ActiveContour[] { c1, c2 };
			
			points.clear();
			points.addAll(c1.points);
			
			return null;
		}
		else
		{
			if (c2area < minArea)
				return new ActiveContour[] {};
			
			points.clear();
			points.addAll(c2.points);
			
			return null;
		}
	}
	
	void move()
	{
		Vector3d force;
		double maxDisp = contour_resolution.getValue() * 0.1;
		
		int index = 0;
		// double dispSum = 0;
		
		for (Point3d p : points)
		{
			force = finalForces[index];
			force.add(feedbackForces[index]);
			
			double disp = force.length();
			if (disp > maxDisp)
				force.scale(maxDisp / disp);
			
			// dispSum += (disp > maxDisp ? maxDisp : disp);
			// TODO something with dispSum
			
			p.add(force);
			force.set(0, 0, 0);
			
			finalForces[index].set(0, 0, 0);
			feedbackForces[index].set(0, 0, 0);
			
			index++;
		}
		normalsNeedUpdate = true;
		boundingSphereNeedsUpdate = true;
		
		if (convergence == null)
			return;
		
		double value = getDimension(1);
		convergence.add(value);
	}
	
	private void updateNormalsIfNeeded()
	{
		if (!normalsNeedUpdate)
			return;
		
		int n = points.size();
		
		// first point
		{
			Point3d p1 = points.get(n - 1);
			Point3d p2 = points.get(1);
			contourNormals[0].normalize(new Vector3d(p2.y - p1.y, p1.x - p2.x, 0));
		}
		
		// middle points
		for (int i = 1; i < n - 1; i++)
		{
			Point3d p1 = points.get(i - 1);
			Point3d p2 = points.get(i + 1);
			contourNormals[i].normalize(new Vector3d(p2.y - p1.y, p1.x - p2.x, 0));
		}
		
		// last point
		{
			Point3d p1 = points.get(n - 2);
			Point3d p2 = points.get(0);
			contourNormals[n - 1].normalize(new Vector3d(p2.y - p1.y, p1.x - p2.x, 0));
		}
		
		normalsNeedUpdate = false;
	}
	
	public BoundingSphere getBoundingSphere()
	{
		if (!boundingSphereNeedsUpdate)
			return boundingSphere;
		
		Point3d center = new Point3d();
		double radius = 0;
		
		// center calculation
		{
			double nbPts = 0;
			for (Point3d p : points)
			{
				center.add(p);
				nbPts++;
			}
			center.scale(1.0 / nbPts);
		}
		boundingSphere.setCenter(center);
		
		// radius calculation
		{
			for (Point3d p : points)
			{
				double d = p.distance(center);
				
				if (d > radius)
					radius = d;
			}
		}
		boundingSphere.setRadius(radius);
		boundingSphereNeedsUpdate = false;
		return boundingSphere;
	}
	
	/**
	 * Update edge term of the contour evolution according to the image gradient
	 * 
	 * @param weight
	 * @param edge_data
	 */
	public void updateEdgeForces(IcyBufferedImage edgeDataX, IcyBufferedImage edgeDataY, double weight)
	{
		int n = points.size();
		
		for (int i = 0; i < n; i++)
		{
			Point3d p = points.get(i);
			Vector3d f = finalForces[i];
			f.x += weight * getPixelValue(edgeDataX, p.x, p.y);
			f.y += weight * getPixelValue(edgeDataY, p.x, p.y);
		}
	}
	
	/**
	 * Update region term of the contour evolution according to the Chan-Vese-Mumford-Shah
	 * functional
	 * 
	 * @param region_data
	 *            the image data (must a double-type image of range [0-1])
	 * @param weight
	 *            the weight of the data attachment term
	 * @param cin
	 *            the intensity mean inside the contour
	 * @param cout
	 *            the intensity mean outside the contour
	 * @param sensitivity
	 *            set 1 for default, lower than 1 for high SNRs and vice-versa
	 */
	void updateRegionForces(IcyBufferedImage region_data, double weight, double cin, double cout, double sensitivity)
	{
		if (sensitivity == 0)
			sensitivity = 1.0;
		
		updateNormalsIfNeeded();
		
		Point3d p;
		Vector3d f, norm;
		double val, inDiff, outDiff, sum;
		int n = points.size();
		
		for (int i = 0; i < n; i++)
		{
			try
			{
				p = points.get(i);
				f = finalForces[i];
				norm = contourNormals[i];
				val = getPixelValue(region_data, p.x, p.y);
				inDiff = val - cin;
				outDiff = val - cout;
				
				sum = weight * contour_resolution.getValue() * (sensitivity * (outDiff * outDiff) - (inDiff * inDiff) / sensitivity);
				// sum = weight * contourRes * ((outDiff * outDiff) - (inDiff * inDiff)) /
				// ratio;
				// System.out.println(sum);
				f.x += sum * norm.x;
				f.y += sum * norm.y;
			}
			catch (Exception e)
			{
			}
			
		}
	}
	
	/**
	 * Caluculates the 2D image value at the given real coordinates by bilinear interpolation
	 * 
	 * @param x
	 *            the X-coordinate of the point
	 * @param y
	 *            the Y-coordinate of the point
	 * @return the interpolated image value at the given coordinates
	 */
	private double getPixelValue(IcyBufferedImage image, double x, double y)
	{
		final int width = image.getWidth();
		
		if (x < 0 || x > width || y < 0 || y > image.getHeight())
			return 0;
		
		double value = 0;
		
		final int i = (int) Math.round(x);
		final int j = (int) Math.round(y);
		final int offset = i + j * width;
		final int offset_plus_1 = offset + 1; // saves 1 addition
		double[] data = image.getDataXYAsDouble(0);
		
		x -= i;
		y -= j;
		
		final double mx = 1 - x;
		final double my = 1 - y;
		
		try
		{
			value += mx * my * data[offset];
			value += x * my * data[offset_plus_1];
			value += mx * y * data[offset + width];
			value += x * y * data[offset_plus_1 + width];
		}
		catch (final java.lang.ArrayIndexOutOfBoundsException aioobE)
		{
			value = 0;
		}
		
		return value;
	}
	
	void updateInternalForces(double weight)
	{
		int n = points.size();
		
		Vector3d f;
		Point3d prev, curr, next;
		
		// first point
		prev = points.get(n - 1);
		curr = points.get(0);
		next = points.get(1);
		
		f = finalForces[0];
		f.x += weight * (prev.x + next.x - 2 * curr.x);
		f.y += weight * (prev.y + next.y - 2 * curr.y);
		
		// middle points
		for (int i = 1; i < n - 1; i++)
		{
			f = finalForces[i];
			prev = points.get(i - 1);
			curr = points.get(i);
			next = points.get(i + 1);
			
			f.x += weight * (prev.x + next.x - 2 * curr.x);
			f.y += weight * (prev.y + next.y - 2 * curr.y);
		}
		
		// last point
		f = finalForces[n - 1];
		prev = points.get(n - 2);
		curr = points.get(n - 1);
		next = points.get(0);
		
		f.x += weight * (prev.x + next.x - 2 * curr.x);// / contour_resolution.getValue();
		f.y += weight * (prev.y + next.y - 2 * curr.y);// / contour_resolution.getValue();
	}
	
	/**
	 * Computes the feedback forces yielded by the penetration of the current contour into the
	 * target contour
	 * 
	 * @param target
	 *            the contour that is being penetrated
	 * @return the number of actual point-mesh intersection tests
	 */
	int updateFeedbackForces(ActiveContour target)
	{
		updateNormalsIfNeeded();
		
		BoundingSphere targetSphere = target.getBoundingSphere();
		Point3d targetCenter = new Point3d();
		targetSphere.getCenter(targetCenter);
		double targetRadius = targetSphere.getRadius();
		
		double penetration = 0;
		
		int tests = 0;
		
		int index = 0;
		for (Point3d p : points)
		{
			Vector3d feedbackForce = feedbackForces[index];
			
			double distance = p.distance(targetCenter);
			
			if (distance < targetRadius)
			{
				tests++;
				
				if ((penetration = target.isInside(p, targetCenter)) > 0)
				{
					feedbackForce.scale(penetration * -2.0, contourNormals[index]);
				}
			}
			index++;
		}
		
		return tests;
	}
	
	/**
	 * Tests whether the given point is inside the contour, and if so returns the penetration depth
	 * of this point. <br>
	 * This methods computes the number of intersections between the contour and a semi-infinite
	 * line starting from the contour center and passing through the given point. The point is thus
	 * considered inside if the number of intersections is odd (Jordan curve theorem).<br>
	 * Implementation note: the AWT Line2D class only provides a "segment to segment" intersection
	 * test instead of a "semi-infinite line to segment" test, meaning that one must "fake" a
	 * semi-infinite line using a big segment. This is done by building a segment originating from
	 * the given point and leaving in the opposite direction of the contour center. The full segment
	 * can be written in algebraic coordinates as
	 * 
	 * <pre>
	 * [PQ] where Q = P + n * CP
	 * </pre>
	 * 
	 * , where n is chosen arbitrarily large.
	 * 
	 * @param c
	 *            a contour
	 * @param p
	 *            a point to test
	 * @return true if the point is inside the contour
	 */
	public double isInside(Point3d p, Point3d center)
	{
		Point3d q = new Point3d(p.x + 10000 * (p.x - center.x), p.y + 10000 * (p.y - center.y), 0);
		
		int nb = 0;
		int nbPtsM1 = points.size() - 1;
		double dist = 0, minDist = Double.MAX_VALUE;
		
		// all points but the last
		for (int i = 0; i < nbPtsM1; i++)
		{
			Point3d p1 = points.get(i);
			Point3d p2 = points.get(i + 1);
			
			if (Line2D.linesIntersect(p1.x, p1.y, p2.x, p2.y, p.x, p.y, q.x, q.y))
			{
				nb++;
				dist = Line2D.ptLineDist(p1.x, p1.y, p2.x, p2.y, p.x, p.y);
				if (dist < minDist)
					minDist = dist;
			}
		}
		
		// last point
		Point3d p1 = points.get(nbPtsM1);
		Point3d p2 = points.get(0);
		if (Line2D.linesIntersect(p1.x, p1.y, p2.x, p2.y, p.x, p.y, q.x, q.y))
		{
			nb++;
			dist = Line2D.ptLineDist(p1.x, p1.y, p2.x, p2.y, p.x, p.y);
			if (dist < minDist)
				minDist = dist;
		}
		
		// return (nb % 2) == 0;
		return (nb % 2 == 1) ? minDist : 0.0;
		
	}
	
	public double getDimension(int order)
	{
		if (points.size() <= 1)
			return 0;
		
		switch (order)
		{
		
			case 0: // number of points
			{
				return points.size();
			}
			
			case 1: // perimeter
			{
				Point3d p1;
				Point3d p2;
				double l = 0.0;
				int size = points.size();
				for (int i = 0; i < size; i++)
				{
					p1 = points.get(i);
					p2 = points.get((i + 1) % size);
					l += Math.abs(p1.distance(p2));
				}
				return l;
			}
			case 2: // area
			{
				int nm1 = points.size() - 1;
				double area = 0;
				
				// all points but the last
				for (int i = 0; i < nm1; i++)
				{
					Point3d p1 = points.get(i);
					Point3d p2 = points.get(i + 1);
					area += (p2.x * p1.y - p1.x * p2.y) * 0.5;
				}
				
				// last point
				Point3d p1 = points.get(nm1);
				Point3d p2 = points.get(0);
				area += (p2.x * p1.y - p1.x * p2.y) * 0.5;
				
				return Math.abs(area);
			}
			default:
				throw new UnsupportedOperationException("Dimension " + order + " not implemented");
		}
	}
	
	@Override
	public void paint(Graphics2D g, Sequence sequence, IcyCanvas canvas)
	{
		if (getT() != canvas.getPositionT() || !enabled)
			return;
		
		int fontSize = (int) ROI2D.canvasToImageLogDeltaX(canvas, 20);
		if (fontSize < 1)
			fontSize = 1;
		Font font = new Font("Arial", Font.BOLD, fontSize);
		g.setFont(font);
		
		double stroke = Math.max(ROI.canvasToImageLogDeltaX(canvas, 3), ROI.canvasToImageLogDeltaY(canvas, 3));
		
		g.setStroke(new BasicStroke((float) stroke));
		
		g.setColor(color);
		
		g.draw(path);
	}
}
