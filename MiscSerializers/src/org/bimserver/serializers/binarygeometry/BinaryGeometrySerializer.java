package org.bimserver.serializers.binarygeometry;

/******************************************************************************
 * Copyright (C) 2009-2014  BIMserver.org
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *****************************************************************************/

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.HashSet;

import org.bimserver.geometry.Matrix;
import org.bimserver.models.ifc2x3tc1.GeometryData;
import org.bimserver.models.ifc2x3tc1.GeometryInfo;
import org.bimserver.models.ifc2x3tc1.Ifc2x3tc1Package;
import org.bimserver.models.ifc2x3tc1.IfcMaterial;
import org.bimserver.models.ifc2x3tc1.IfcMaterialLayer;
import org.bimserver.models.ifc2x3tc1.IfcMaterialLayerSet;
import org.bimserver.models.ifc2x3tc1.IfcMaterialLayerSetUsage;
import org.bimserver.models.ifc2x3tc1.IfcMaterialSelect;
import org.bimserver.models.ifc2x3tc1.IfcPresentationStyleAssignment;
import org.bimserver.models.ifc2x3tc1.IfcPresentationStyleSelect;
import org.bimserver.models.ifc2x3tc1.IfcProduct;
import org.bimserver.models.ifc2x3tc1.IfcProductRepresentation;
import org.bimserver.models.ifc2x3tc1.IfcRelAssociates;
import org.bimserver.models.ifc2x3tc1.IfcRelAssociatesMaterial;
import org.bimserver.models.ifc2x3tc1.IfcRepresentation;
import org.bimserver.models.ifc2x3tc1.IfcRepresentationItem;
import org.bimserver.models.ifc2x3tc1.IfcSlab;
import org.bimserver.models.ifc2x3tc1.IfcSlabTypeEnum;
import org.bimserver.models.ifc2x3tc1.IfcStyledItem;
import org.bimserver.models.ifc2x3tc1.IfcSurfaceStyle;
import org.bimserver.plugins.serializers.AbstractGeometrySerializer;
import org.bimserver.plugins.serializers.SerializerException;
import org.eclipse.emf.common.util.EList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BinaryGeometrySerializer extends AbstractGeometrySerializer {
	private static final Logger LOGGER = LoggerFactory.getLogger(BinaryGeometrySerializer.class);
	private static final byte FORMAT_VERSION = 3;
	private final HashMap<String, HashMap<String, HashSet<Long>>> typeMaterialGeometryRel = new HashMap<String, HashMap<String, HashSet<Long>>>();

	@Override
	public void reset() {
		setMode(Mode.BODY);
	}

	@Override
	protected boolean write(OutputStream outputStream) throws SerializerException {
		if (getMode() == Mode.BODY) {
			try {
				calculateGeometryExtents();
				writeGeometries(outputStream);
			} catch (Exception e) {
				LOGGER.error("", e);
			}
			setMode(Mode.FINISHED);
			return true;
		} else if (getMode() == Mode.FINISHED) {
			return false;
		}
		return false;
	}

	private void writeGeometries(OutputStream outputStream) throws IOException {
		DataOutputStream dataOutputStream = new DataOutputStream(outputStream);
		dataOutputStream.writeUTF("BGS");
		dataOutputStream.writeByte(FORMAT_VERSION);
		
		Bounds modelBounds = new Bounds();
		int nrObjects = 0;
		for (IfcProduct ifcProduct : getModel().getAllWithSubTypes(IfcProduct.class)) {
			GeometryInfo geometryInfo = ifcProduct.getGeometry();
			if (geometryInfo != null) {
				Bounds objectBounds = new Bounds(new Float3(geometryInfo.getMinBounds().getX(), geometryInfo.getMinBounds().getY(), geometryInfo.getMinBounds()
						.getZ()), new Float3(geometryInfo.getMaxBounds().getX(), geometryInfo.getMaxBounds().getY(), geometryInfo.getMaxBounds().getZ()));
				modelBounds.integrate(objectBounds);
				nrObjects++;
			}
		}
		modelBounds.writeTo(dataOutputStream);
		dataOutputStream.writeInt(nrObjects);
		int bytesSaved = 0;
		int bytesTotal = 0;
		for (IfcProduct ifcProduct : getModel().getAllWithSubTypes(IfcProduct.class)) {
			GeometryInfo geometryInfo = ifcProduct.getGeometry();
			if (geometryInfo != null) {
				String materialName = ifcProduct.eClass().getName();
				try {
					materialName = getMaterial(ifcProduct);
				} catch (Exception e) {
				}
				dataOutputStream.writeUTF(materialName);
				String type = null;
				if (ifcProduct instanceof IfcSlab && ((IfcSlab) ifcProduct).getPredefinedType() == IfcSlabTypeEnum.ROOF) {
					type = "IfcRoof";
				} else {
					type = ifcProduct.eClass().getName();
				}
				dataOutputStream.writeUTF(type);
				
				dataOutputStream.writeLong(ifcProduct.getOid());

				int skip = 4 - (dataOutputStream.size() % 4);
				if(skip != 0 && skip != 4) {
					dataOutputStream.write(new byte[skip]);
				}
				
				Bounds objectBounds = new Bounds(geometryInfo.getMinBounds(), geometryInfo.getMaxBounds());
				objectBounds.writeTo(dataOutputStream);
				
				dataOutputStream.writeInt(geometryInfo.getPrimitiveCount() * 3);
	
				GeometryData geometryData = geometryInfo.getData();
				byte[] vertices = geometryData.getVertices();
				ByteBuffer buffer = ByteBuffer.wrap(vertices);
				buffer.order(ByteOrder.nativeOrder());
				bytesTotal += buffer.capacity();
				if (FORMAT_VERSION > 3 && geometryInfo.getTransformation() != null && geometryInfo.getTransformation().size() == 16) {
					bytesSaved += buffer.capacity();
					
					float[] matrix = new float[16];
					EList<Float> list = geometryInfo.getTransformation();
					for (int i=0; i<list.size(); i++) {
						matrix[i] = list.get(i);
					}

					FloatBuffer vertexBuffer = buffer.asFloatBuffer();
					ByteBuffer newByteBuffer = ByteBuffer.allocate(buffer.capacity());
					newByteBuffer.order(buffer.order());
					FloatBuffer newFloatBuffer = newByteBuffer.asFloatBuffer();
					for (int i=0; i<vertexBuffer.capacity(); i+=3) {
						float[] newVector = new float[4];
						float[] oldVector = new float[]{vertexBuffer.get(i), vertexBuffer.get(i + 1), vertexBuffer.get(i + 2), 1};
						Matrix.multiplyMV(newVector, 0, matrix, 0, oldVector, 0);
						newFloatBuffer.put(i, newVector[0]);
						newFloatBuffer.put(i + 1, newVector[1]);
						newFloatBuffer.put(i + 2, newVector[2]);
					}
					buffer = newByteBuffer;
				}
				dataOutputStream.writeInt(buffer.capacity() / 4);
				dataOutputStream.write(buffer.array());
				
				ByteBuffer normalsBuffer = ByteBuffer.wrap(geometryData.getNormals());
				dataOutputStream.writeInt(normalsBuffer.capacity() / 4);
				dataOutputStream.write(normalsBuffer.array());
			}
		}
		if (FORMAT_VERSION > 3) {
			System.out.println((100 * bytesSaved / bytesTotal) + "% saved");
		}
		dataOutputStream.flush();
	}
	
	public String getMaterial(IfcProduct ifcProduct) throws Exception {
		boolean materialFound = false;
		String material = ifcProduct.eClass().getName();
		if (ifcProduct instanceof IfcSlab && ((IfcSlab)ifcProduct).getPredefinedType() == IfcSlabTypeEnum.ROOF) {
			material = Ifc2x3tc1Package.eINSTANCE.getIfcRoof().getName();
		}
		
		IfcMaterialSelect relatingMaterial = null;
		for (IfcRelAssociates ifcRelAssociates : ifcProduct.getHasAssociations()) {
			if (ifcRelAssociates instanceof IfcRelAssociatesMaterial) {
				IfcRelAssociatesMaterial ifcRelAssociatesMaterial = (IfcRelAssociatesMaterial)ifcRelAssociates;
				relatingMaterial = ifcRelAssociatesMaterial.getRelatingMaterial();
			}
		}

		// Try to find the IFC material name
		if (relatingMaterial instanceof IfcMaterialLayerSetUsage) {
			IfcMaterialLayerSetUsage mlsu = (IfcMaterialLayerSetUsage) relatingMaterial;
			IfcMaterialLayerSet forLayerSet = mlsu.getForLayerSet();
			if (forLayerSet != null) {
				EList<IfcMaterialLayer> materialLayers = forLayerSet.getMaterialLayers();
				for (IfcMaterialLayer ml : materialLayers) {
					IfcMaterial ifcMaterial = ml.getMaterial();
					if (ifcMaterial != null) {
						String name = ifcMaterial.getName();
						String filterSpaces = fitNameForQualifiedName(name);
//						materialFound = surfaceStyleIds.contains(filterSpaces);
//						if (materialFound) {
							material = filterSpaces;
//						}
					}
				}
			}
		} else if (relatingMaterial instanceof IfcMaterial) {
			IfcMaterial ifcMaterial = (IfcMaterial) relatingMaterial;
			String name = ifcMaterial.getName();
			String filterSpaces = fitNameForQualifiedName(name);
//			materialFound = surfaceStyleIds.contains(filterSpaces);
//			if (materialFound) {
				material = filterSpaces;
//			}
		}

		// If no material was found then derive one from the presentation style
		if (!materialFound) {
			IfcProductRepresentation representation = ifcProduct.getRepresentation();
			if (representation != null) {
				EList<IfcRepresentation> representations = representation.getRepresentations();
				for (IfcRepresentation rep : representations) {
					EList<IfcRepresentationItem> items = rep.getItems();
					for (IfcRepresentationItem item : items) {
						if (item instanceof IfcStyledItem) {
							material = processStyledItem(material, (IfcStyledItem) item);
						} else {
							EList<IfcStyledItem> styledByItem = item.getStyledByItem();
							for (IfcStyledItem sItem : styledByItem) {
								material = processStyledItem(material, sItem);
							}
						}
					}
				}
			}
		}

		HashMap<String, HashSet<Long>> materialGeometryRel = typeMaterialGeometryRel.get(ifcProduct.eClass().getName());
		if (materialGeometryRel == null) {
			materialGeometryRel = new HashMap<String, HashSet<Long>>();
			typeMaterialGeometryRel.put(ifcProduct.eClass().getName(), materialGeometryRel);
		}

		HashSet<Long> hashSet = materialGeometryRel.get(material);
		if (hashSet == null) {
			hashSet = new HashSet<Long>();
			materialGeometryRel.put(material, hashSet);
		}
		hashSet.add(ifcProduct.getOid());
		if (material == null) {
			return "UNKNOWN";
		}
		return material;
	}
	
	private String processStyledItem(String material, IfcStyledItem sItem) {
		for (IfcStyledItem ifc : sItem.getStyledByItem()) {
			processStyledItem(material, ifc);
		}
		EList<IfcPresentationStyleAssignment> styles = sItem.getStyles();
		for (IfcPresentationStyleAssignment sa : styles) {
			EList<IfcPresentationStyleSelect> styles2 = sa.getStyles();
			for (IfcPresentationStyleSelect pss : styles2) {
				if (pss instanceof IfcSurfaceStyle) {
					IfcSurfaceStyle ss = (IfcSurfaceStyle) pss;
					material = "" + ss.getOid();
				}
			}
		}
		return material;
	}
	
	private String fitNameForQualifiedName(String name) {
		if (name == null) {
			return "Null";
		}
		StringBuilder builder = new StringBuilder(name);
		int indexOfChar = builder.indexOf(" ");
		while (indexOfChar >= 0) {
			builder.deleteCharAt(indexOfChar);
			indexOfChar = builder.indexOf(" ");
		}
		indexOfChar = builder.indexOf(",");
		while (indexOfChar >= 0) {
			builder.setCharAt(indexOfChar, '_');
			indexOfChar = builder.indexOf(",");
		}
		indexOfChar = builder.indexOf("/");
		while (indexOfChar >= 0) {
			builder.setCharAt(indexOfChar, '_');
			indexOfChar = builder.indexOf("/");
		}
		indexOfChar = builder.indexOf("*");
		while (indexOfChar >= 0) {
			builder.setCharAt(indexOfChar, '_');
			indexOfChar = builder.indexOf("/");
		}
		return builder.toString();
	}
}