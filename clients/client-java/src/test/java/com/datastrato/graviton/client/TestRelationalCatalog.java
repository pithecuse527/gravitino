/*
 * Copyright 2023 Datastrato.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.graviton.client;

import static com.datastrato.graviton.dto.rel.PartitionUtils.toPartitions;
import static com.datastrato.graviton.rel.transforms.Transforms.day;
import static com.datastrato.graviton.rel.transforms.Transforms.field;
import static com.datastrato.graviton.rel.transforms.Transforms.identity;
import static org.apache.hc.core5.http.HttpStatus.SC_BAD_REQUEST;
import static org.apache.hc.core5.http.HttpStatus.SC_CONFLICT;
import static org.apache.hc.core5.http.HttpStatus.SC_INTERNAL_SERVER_ERROR;
import static org.apache.hc.core5.http.HttpStatus.SC_NOT_FOUND;
import static org.apache.hc.core5.http.HttpStatus.SC_OK;

import com.datastrato.graviton.Catalog;
import com.datastrato.graviton.NameIdentifier;
import com.datastrato.graviton.Namespace;
import com.datastrato.graviton.dto.AuditDTO;
import com.datastrato.graviton.dto.CatalogDTO;
import com.datastrato.graviton.dto.rel.ColumnDTO;
import com.datastrato.graviton.dto.rel.DistributionDTO;
import com.datastrato.graviton.dto.rel.DistributionDTO.Strategy;
import com.datastrato.graviton.dto.rel.ExpressionPartitionDTO.Expression;
import com.datastrato.graviton.dto.rel.ExpressionPartitionDTO.FieldExpression;
import com.datastrato.graviton.dto.rel.Partition;
import com.datastrato.graviton.dto.rel.SchemaDTO;
import com.datastrato.graviton.dto.rel.SortOrderDTO;
import com.datastrato.graviton.dto.rel.SortOrderDTO.NullOrdering;
import com.datastrato.graviton.dto.rel.TableDTO;
import com.datastrato.graviton.dto.requests.CatalogCreateRequest;
import com.datastrato.graviton.dto.requests.SchemaCreateRequest;
import com.datastrato.graviton.dto.requests.SchemaUpdateRequest;
import com.datastrato.graviton.dto.requests.SchemaUpdatesRequest;
import com.datastrato.graviton.dto.requests.TableCreateRequest;
import com.datastrato.graviton.dto.requests.TableUpdateRequest;
import com.datastrato.graviton.dto.requests.TableUpdatesRequest;
import com.datastrato.graviton.dto.responses.CatalogResponse;
import com.datastrato.graviton.dto.responses.DropResponse;
import com.datastrato.graviton.dto.responses.EntityListResponse;
import com.datastrato.graviton.dto.responses.ErrorResponse;
import com.datastrato.graviton.dto.responses.SchemaResponse;
import com.datastrato.graviton.dto.responses.TableResponse;
import com.datastrato.graviton.exceptions.NoSuchCatalogException;
import com.datastrato.graviton.exceptions.NoSuchSchemaException;
import com.datastrato.graviton.exceptions.NoSuchTableException;
import com.datastrato.graviton.exceptions.NonEmptySchemaException;
import com.datastrato.graviton.exceptions.RESTException;
import com.datastrato.graviton.exceptions.SchemaAlreadyExistsException;
import com.datastrato.graviton.exceptions.TableAlreadyExistsException;
import com.datastrato.graviton.rel.Schema;
import com.datastrato.graviton.rel.SortOrder;
import com.datastrato.graviton.rel.Table;
import com.datastrato.graviton.rel.TableChange;
import com.datastrato.graviton.rel.transforms.Transform;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.substrait.type.Type;
import io.substrait.type.TypeCreator;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.apache.hc.core5.http.Method;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class TestRelationalCatalog extends TestBase {

  private static Catalog catalog;

  private static GravitonMetaLake metalake;

  private static final String metalakeName = "testMetalake";

  private static final String catalogName = "testCatalog";

  private static final String provider = "test";

  @BeforeAll
  public static void setUp() throws Exception {
    TestBase.setUp();

    metalake = TestGravitonMetalake.createMetalake(client, metalakeName);

    CatalogDTO mockCatalog =
        new CatalogDTO.Builder()
            .withName(catalogName)
            .withType(CatalogDTO.Type.RELATIONAL)
            .withProvider(provider)
            .withComment("comment")
            .withProperties(ImmutableMap.of("k1", "k2"))
            .withAudit(
                new AuditDTO.Builder().withCreator("creator").withCreateTime(Instant.now()).build())
            .build();

    CatalogCreateRequest catalogCreateRequest =
        new CatalogCreateRequest(
            catalogName,
            CatalogDTO.Type.RELATIONAL,
            provider,
            "comment",
            ImmutableMap.of("k1", "k2"));
    CatalogResponse catalogResponse = new CatalogResponse(mockCatalog);
    buildMockResource(
        Method.POST,
        "/api/metalakes/" + metalakeName + "/catalogs",
        catalogCreateRequest,
        catalogResponse,
        SC_OK);

    catalog =
        metalake.createCatalog(
            NameIdentifier.of(metalakeName, catalogName),
            CatalogDTO.Type.RELATIONAL,
            provider,
            "comment",
            ImmutableMap.of("k1", "k2"));
  }

  @Test
  public void testListSchemas() throws JsonProcessingException {
    Namespace schemaNs = Namespace.of(metalakeName, catalogName);
    NameIdentifier schema1 = NameIdentifier.of(schemaNs, "schema1");
    NameIdentifier schema2 = NameIdentifier.of(schemaNs, "schema2");
    String schemaPath = withSlash(RelationalCatalog.formatSchemaRequestPath(schemaNs));

    EntityListResponse resp = new EntityListResponse(new NameIdentifier[] {schema1, schema2});
    buildMockResource(Method.GET, schemaPath, null, resp, SC_OK);
    NameIdentifier[] schemas = catalog.asSchemas().listSchemas(schemaNs);

    Assertions.assertEquals(2, schemas.length);
    Assertions.assertEquals(schema1, schemas[0]);
    Assertions.assertEquals(schema2, schemas[1]);

    // Test return empty schema list
    EntityListResponse emptyResp = new EntityListResponse(new NameIdentifier[] {});
    buildMockResource(Method.GET, schemaPath, null, emptyResp, SC_OK);
    NameIdentifier[] emptySchemas = catalog.asSchemas().listSchemas(schemaNs);
    Assertions.assertEquals(0, emptySchemas.length);

    // Test throw NoSuchCatalogException
    ErrorResponse errorResp =
        ErrorResponse.notFound(NoSuchCatalogException.class.getSimpleName(), "catalog not found");
    buildMockResource(Method.GET, schemaPath, null, errorResp, SC_NOT_FOUND);
    Throwable ex =
        Assertions.assertThrows(
            NoSuchCatalogException.class, () -> catalog.asSchemas().listSchemas(schemaNs));
    Assertions.assertTrue(ex.getMessage().contains("catalog not found"));

    // Test throw RuntimeException
    ErrorResponse errorResp1 = ErrorResponse.internalError("internal error");
    buildMockResource(Method.GET, schemaPath, null, errorResp1, SC_INTERNAL_SERVER_ERROR);
    Throwable ex1 =
        Assertions.assertThrows(
            RuntimeException.class, () -> catalog.asSchemas().listSchemas(schemaNs));
    Assertions.assertTrue(ex1.getMessage().contains("internal error"));

    // Test throw unparsed system error
    buildMockResource(Method.GET, schemaPath, null, "unparsed error", SC_BAD_REQUEST);
    Throwable ex2 =
        Assertions.assertThrows(
            RESTException.class, () -> catalog.asSchemas().listSchemas(schemaNs));
    Assertions.assertTrue(ex2.getMessage().contains("unparsed error"));
  }

  @Test
  public void testCreateSchema() throws JsonProcessingException {
    NameIdentifier schemaId = NameIdentifier.of(metalakeName, catalogName, "schema1");
    String schemaPath = withSlash(RelationalCatalog.formatSchemaRequestPath(schemaId.namespace()));
    SchemaDTO schema = createMockSchema("schema1", "comment", Collections.emptyMap());

    SchemaCreateRequest req = new SchemaCreateRequest("schema1", "comment", Collections.emptyMap());
    SchemaResponse resp = new SchemaResponse(schema);
    buildMockResource(Method.POST, schemaPath, req, resp, SC_OK);

    Schema createdSchema =
        catalog.asSchemas().createSchema(schemaId, "comment", Collections.emptyMap());
    Assertions.assertEquals("schema1", createdSchema.name());
    Assertions.assertEquals("comment", createdSchema.comment());
    Assertions.assertEquals(Collections.emptyMap(), createdSchema.properties());

    // Test throw NoSuchCatalogException
    ErrorResponse errorResp =
        ErrorResponse.notFound(NoSuchCatalogException.class.getSimpleName(), "catalog not found");
    buildMockResource(Method.POST, schemaPath, req, errorResp, SC_NOT_FOUND);

    Throwable ex =
        Assertions.assertThrows(
            NoSuchCatalogException.class,
            () -> catalog.asSchemas().createSchema(schemaId, "comment", Collections.emptyMap()));
    Assertions.assertTrue(ex.getMessage().contains("catalog not found"));

    // Test throw SchemaAlreadyExistsException
    ErrorResponse errorResp1 =
        ErrorResponse.alreadyExists(
            SchemaAlreadyExistsException.class.getSimpleName(), "schema already exists");
    buildMockResource(Method.POST, schemaPath, req, errorResp1, SC_CONFLICT);

    Throwable ex1 =
        Assertions.assertThrows(
            SchemaAlreadyExistsException.class,
            () -> catalog.asSchemas().createSchema(schemaId, "comment", Collections.emptyMap()));
    Assertions.assertTrue(ex1.getMessage().contains("schema already exists"));
  }

  @Test
  public void testLoadSchema() throws JsonProcessingException {
    NameIdentifier schemaId = NameIdentifier.of(metalakeName, catalogName, "schema1");
    String schemaPath =
        withSlash(
            RelationalCatalog.formatSchemaRequestPath(schemaId.namespace())
                + "/"
                + schemaId.name());
    SchemaDTO schema = createMockSchema("schema1", "comment", Collections.emptyMap());

    SchemaResponse resp = new SchemaResponse(schema);
    buildMockResource(Method.GET, schemaPath, null, resp, SC_OK);

    Schema loadedSchema = catalog.asSchemas().loadSchema(schemaId);
    Assertions.assertEquals("schema1", loadedSchema.name());
    Assertions.assertEquals("comment", loadedSchema.comment());
    Assertions.assertEquals(Collections.emptyMap(), loadedSchema.properties());

    // Test throw NoSuchSchemaException
    ErrorResponse errorResp1 =
        ErrorResponse.notFound(NoSuchSchemaException.class.getSimpleName(), "schema not found");
    buildMockResource(Method.GET, schemaPath, null, errorResp1, SC_NOT_FOUND);

    Throwable ex1 =
        Assertions.assertThrows(
            NoSuchSchemaException.class, () -> catalog.asSchemas().loadSchema(schemaId));
    Assertions.assertTrue(ex1.getMessage().contains("schema not found"));
  }

  @Test
  public void testSetSchemaProperty() throws JsonProcessingException {
    NameIdentifier ident = NameIdentifier.of(metalakeName, catalogName, "schema1");
    SchemaUpdateRequest.SetSchemaPropertyRequest req =
        new SchemaUpdateRequest.SetSchemaPropertyRequest("k1", "v1");
    SchemaDTO expectedSchema = createMockSchema("schema1", "comment", ImmutableMap.of("k1", "v1"));

    testAlterSchema(ident, req, expectedSchema);
  }

  @Test
  public void testRemoveSchemaProperty() throws JsonProcessingException {
    NameIdentifier ident = NameIdentifier.of(metalakeName, catalogName, "schema1");
    SchemaUpdateRequest.RemoveSchemaPropertyRequest req =
        new SchemaUpdateRequest.RemoveSchemaPropertyRequest("k1");
    SchemaDTO expectedSchema = createMockSchema("schema1", "comment", Collections.emptyMap());

    testAlterSchema(ident, req, expectedSchema);
  }

  @Test
  public void testDropSchema() throws JsonProcessingException {
    NameIdentifier ident = NameIdentifier.of(metalakeName, catalogName, "schema1");
    String schemaPath =
        withSlash(
            RelationalCatalog.formatSchemaRequestPath(ident.namespace()) + "/" + ident.name());
    DropResponse resp = new DropResponse(true);
    buildMockResource(Method.DELETE, schemaPath, null, resp, SC_OK);

    Assertions.assertTrue(catalog.asSchemas().dropSchema(ident, false));

    // Test with cascade to ture
    DropResponse resp1 = new DropResponse(true);
    buildMockResource(
        Method.DELETE, schemaPath, ImmutableMap.of("cascade", "true"), null, resp1, SC_OK);

    Assertions.assertTrue(catalog.asSchemas().dropSchema(ident, true));

    // Test throw NonEmptySchemaException
    ErrorResponse errorResp =
        ErrorResponse.nonEmpty(
            NonEmptySchemaException.class.getSimpleName(), "schema is not empty");
    buildMockResource(Method.DELETE, schemaPath, null, errorResp, SC_CONFLICT);

    Throwable ex =
        Assertions.assertThrows(
            NonEmptySchemaException.class, () -> catalog.asSchemas().dropSchema(ident, true));

    Assertions.assertTrue(ex.getMessage().contains("schema is not empty"));
  }

  @Test
  public void testListTables() throws JsonProcessingException {
    NameIdentifier table1 = NameIdentifier.of(metalakeName, catalogName, "schema1", "table1");
    NameIdentifier table2 = NameIdentifier.of(metalakeName, catalogName, "schema1", "table2");
    String tablePath = withSlash(RelationalCatalog.formatTableRequestPath(table1.namespace()));

    EntityListResponse resp = new EntityListResponse(new NameIdentifier[] {table1, table2});
    buildMockResource(Method.GET, tablePath, null, resp, SC_OK);
    NameIdentifier[] tables = catalog.asTableCatalog().listTables(table1.namespace());

    Assertions.assertEquals(2, tables.length);
    Assertions.assertEquals(table1, tables[0]);
    Assertions.assertEquals(table2, tables[1]);

    // Test throw NoSuchSchemaException
    ErrorResponse errorResp =
        ErrorResponse.notFound(NoSuchSchemaException.class.getSimpleName(), "schema not found");
    buildMockResource(Method.GET, tablePath, null, errorResp, SC_NOT_FOUND);

    Throwable ex =
        Assertions.assertThrows(
            NoSuchSchemaException.class,
            () -> catalog.asTableCatalog().listTables(table1.namespace()));
    Assertions.assertTrue(ex.getMessage().contains("schema not found"));

    // Test throw RuntimeException
    ErrorResponse errorResp1 = ErrorResponse.internalError("runtime exception");
    buildMockResource(Method.GET, tablePath, null, errorResp1, SC_INTERNAL_SERVER_ERROR);

    Throwable ex1 =
        Assertions.assertThrows(
            RuntimeException.class, () -> catalog.asTableCatalog().listTables(table1.namespace()));
    Assertions.assertTrue(ex1.getMessage().contains("runtime exception"));

    // Test throw unparsed system error
    buildMockResource(Method.GET, tablePath, null, "unparsed error", SC_CONFLICT);
    Throwable ex2 =
        Assertions.assertThrows(
            RuntimeException.class, () -> catalog.asTableCatalog().listTables(table1.namespace()));
    Assertions.assertTrue(ex2.getMessage().contains("unparsed error"));
  }

  @Test
  public void testCreateTable() throws JsonProcessingException {
    NameIdentifier tableId = NameIdentifier.of(metalakeName, catalogName, "schema1", "table1");
    String tablePath = withSlash(RelationalCatalog.formatTableRequestPath(tableId.namespace()));

    ColumnDTO[] columns =
        new ColumnDTO[] {
          createMockColumn("col1", TypeCreator.NULLABLE.I8, "comment1"),
          createMockColumn("col2", TypeCreator.NULLABLE.STRING, "comment2")
        };

    DistributionDTO distributionDTO = createMockDistributionDTO("col1", 10);
    SortOrderDTO[] sortOrderDTOs = createMockSortOrderDTO("col2", SortOrderDTO.Direction.DESC);

    TableDTO expectedTable =
        createMockTable(
            "table1",
            columns,
            "comment",
            Collections.emptyMap(),
            new Partition[0],
            DistributionDTO.NONE,
            sortOrderDTOs);
    TableCreateRequest req =
        new TableCreateRequest(
            tableId.name(),
            "comment",
            columns,
            Collections.emptyMap(),
            sortOrderDTOs,
            DistributionDTO.NONE,
            new Partition[0]);
    TableResponse resp = new TableResponse(expectedTable);
    buildMockResource(Method.POST, tablePath, req, resp, SC_OK);

    Table table =
        catalog
            .asTableCatalog()
            .createTable(
                tableId,
                columns,
                "comment",
                Collections.emptyMap(),
                Arrays.stream(sortOrderDTOs)
                    .map(com.datastrato.graviton.dto.util.DTOConverters::fromDTO)
                    .toArray(SortOrder[]::new));
    Assertions.assertEquals(expectedTable.name(), table.name());
    Assertions.assertEquals(expectedTable.comment(), table.comment());
    Assertions.assertEquals(expectedTable.properties(), table.properties());

    Assertions.assertEquals(expectedTable.columns().length, table.columns().length);
    Assertions.assertEquals(expectedTable.columns()[0].name(), table.columns()[0].name());
    Assertions.assertEquals(expectedTable.columns()[0].dataType(), table.columns()[0].dataType());
    Assertions.assertEquals(expectedTable.columns()[0].comment(), table.columns()[0].comment());

    Assertions.assertEquals(expectedTable.columns()[1].name(), table.columns()[1].name());
    Assertions.assertEquals(expectedTable.columns()[1].dataType(), table.columns()[1].dataType());
    Assertions.assertEquals(expectedTable.columns()[1].comment(), table.columns()[1].comment());
    assertTableEquals(expectedTable, table);

    // Test throw NoSuchSchemaException
    ErrorResponse errorResp =
        ErrorResponse.notFound(NoSuchSchemaException.class.getSimpleName(), "schema not found");
    buildMockResource(Method.POST, tablePath, req, errorResp, SC_NOT_FOUND);

    Throwable ex =
        Assertions.assertThrows(
            NoSuchSchemaException.class,
            () ->
                catalog
                    .asTableCatalog()
                    .createTable(
                        tableId,
                        columns,
                        "comment",
                        Collections.emptyMap(),
                        Arrays.stream(sortOrderDTOs)
                            .map(com.datastrato.graviton.dto.util.DTOConverters::fromDTO)
                            .toArray(SortOrder[]::new)));
    Assertions.assertTrue(ex.getMessage().contains("schema not found"));

    // Test throw TableAlreadyExistsException
    ErrorResponse errorResp1 =
        ErrorResponse.alreadyExists(
            TableAlreadyExistsException.class.getSimpleName(), "table already exists");
    buildMockResource(Method.POST, tablePath, req, errorResp1, SC_CONFLICT);

    Throwable ex1 =
        Assertions.assertThrows(
            TableAlreadyExistsException.class,
            () ->
                catalog
                    .asTableCatalog()
                    .createTable(
                        tableId,
                        columns,
                        "comment",
                        Collections.emptyMap(),
                        Arrays.stream(sortOrderDTOs)
                            .map(com.datastrato.graviton.dto.util.DTOConverters::fromDTO)
                            .toArray(SortOrder[]::new)));
    Assertions.assertTrue(ex1.getMessage().contains("table already exists"));
  }

  @Test
  public void testCreatePartitionedTable() throws JsonProcessingException {
    NameIdentifier tableId = NameIdentifier.of(metalakeName, catalogName, "schema1", "table1");
    String tablePath = withSlash(RelationalCatalog.formatTableRequestPath(tableId.namespace()));

    ColumnDTO[] columns =
        new ColumnDTO[] {
          createMockColumn("city", TypeCreator.NULLABLE.I32, "comment1"),
          createMockColumn("dt", TypeCreator.NULLABLE.DATE, "comment2")
        };

    // Test empty partitions
    Transform[] emptyTransform = new Transform[0];
    TableDTO expectedTable =
        createMockTable(
            "table1",
            columns,
            "comment",
            Collections.emptyMap(),
            toPartitions(emptyTransform),
            null,
            new SortOrderDTO[0]);

    TableCreateRequest req =
        new TableCreateRequest(
            tableId.name(),
            "comment",
            columns,
            Collections.emptyMap(),
            new SortOrderDTO[0],
            DistributionDTO.NONE,
            toPartitions(emptyTransform));
    TableResponse resp = new TableResponse(expectedTable);
    buildMockResource(Method.POST, tablePath, req, resp, SC_OK);

    Table table =
        catalog
            .asTableCatalog()
            .createTable(tableId, columns, "comment", Collections.emptyMap(), emptyTransform);
    assertTableEquals(expectedTable, table);

    // Test partitions
    Transform[] transforms = {
      identity(new String[] {columns[0].name()}), day(new String[] {columns[1].name()})
    };
    expectedTable =
        createMockTable(
            "table1",
            columns,
            "comment",
            Collections.emptyMap(),
            toPartitions(transforms),
            DistributionDTO.NONE,
            new SortOrderDTO[0]);

    req =
        new TableCreateRequest(
            tableId.name(),
            "comment",
            columns,
            Collections.emptyMap(),
            new SortOrderDTO[0],
            DistributionDTO.NONE,
            toPartitions(transforms));
    resp = new TableResponse(expectedTable);
    buildMockResource(Method.POST, tablePath, req, resp, SC_OK);

    table =
        catalog
            .asTableCatalog()
            .createTable(tableId, columns, "comment", Collections.emptyMap(), transforms);
    assertTableEquals(expectedTable, table);

    // Test throw TableAlreadyExistsException
    ErrorResponse errorResp1 =
        ErrorResponse.alreadyExists(
            TableAlreadyExistsException.class.getSimpleName(), "table already exists");
    buildMockResource(Method.POST, tablePath, req, errorResp1, SC_CONFLICT);

    Throwable ex1 =
        Assertions.assertThrows(
            TableAlreadyExistsException.class,
            () ->
                catalog
                    .asTableCatalog()
                    .createTable(tableId, columns, "comment", Collections.emptyMap(), transforms));
    Assertions.assertTrue(ex1.getMessage().contains("table already exists"));

    // Test partition field not exist in table
    Transform[] errorTransforms = {identity(new String[] {"not_exist_field"})};
    Throwable ex2 =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () ->
                catalog
                    .asTableCatalog()
                    .createTable(
                        tableId, columns, "comment", Collections.emptyMap(), errorTransforms));
    Assertions.assertTrue(ex2.getMessage().contains("not found in table"));

    // Test empty columns
    Throwable ex3 =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () ->
                catalog
                    .asTableCatalog()
                    .createTable(
                        tableId,
                        new ColumnDTO[0],
                        "comment",
                        Collections.emptyMap(),
                        emptyTransform));
    Assertions.assertTrue(
        ex3.getMessage().contains("\"columns\" field is required and cannot be empty"));
  }

  private void assertTableEquals(TableDTO expected, Table actual) {
    Assertions.assertEquals(expected.name(), actual.name());
    Assertions.assertEquals(expected.comment(), actual.comment());
    Assertions.assertEquals(expected.properties(), actual.properties());

    Assertions.assertArrayEquals(expected.columns(), actual.columns());

    Assertions.assertArrayEquals(expected.partitioning(), actual.partitioning());
  }

  @Test
  public void testLoadTable() throws JsonProcessingException {
    NameIdentifier tableId = NameIdentifier.of(metalakeName, catalogName, "schema1", "table1");
    String tablePath =
        withSlash(
            RelationalCatalog.formatTableRequestPath(tableId.namespace()) + "/" + tableId.name());
    ColumnDTO[] columns =
        new ColumnDTO[] {
          createMockColumn("col1", TypeCreator.NULLABLE.I8, "comment1"),
          createMockColumn("col2", TypeCreator.NULLABLE.STRING, "comment2")
        };

    DistributionDTO distributionDTO = createMockDistributionDTO("col1", 10);
    SortOrderDTO[] sortOrderDTOs = createMockSortOrderDTO("col2", SortOrderDTO.Direction.DESC);

    TableDTO expectedTable =
        createMockTable(
            "table1",
            columns,
            "comment",
            Collections.emptyMap(),
            toPartitions(new Transform[] {field(columns[0])}),
            distributionDTO,
            sortOrderDTOs);

    TableResponse resp = new TableResponse(expectedTable);
    buildMockResource(Method.GET, tablePath, null, resp, SC_OK);

    Table table = catalog.asTableCatalog().loadTable(tableId);
    assertTableEquals(expectedTable, table);

    // Test throw NoSuchTableException
    ErrorResponse errorResp =
        ErrorResponse.notFound(NoSuchTableException.class.getSimpleName(), "table not found");
    buildMockResource(Method.GET, tablePath, null, errorResp, SC_NOT_FOUND);

    Throwable ex =
        Assertions.assertThrows(
            NoSuchTableException.class, () -> catalog.asTableCatalog().loadTable(tableId));
    Assertions.assertTrue(ex.getMessage().contains("table not found"));
  }

  @Test
  public void testRenameTable() throws JsonProcessingException {
    NameIdentifier tableId = NameIdentifier.of(metalakeName, catalogName, "schema1", "table1");
    ColumnDTO[] columns =
        new ColumnDTO[] {createMockColumn("col1", TypeCreator.NULLABLE.I8, "comment1")};

    DistributionDTO distributionDTO = createMockDistributionDTO("col1", 10);
    SortOrderDTO[] sortOrderDTOs = createMockSortOrderDTO("col1", SortOrderDTO.Direction.DESC);

    TableDTO expectedTable =
        createMockTable(
            "table2",
            columns,
            "comment",
            Collections.emptyMap(),
            new Partition[0],
            distributionDTO,
            sortOrderDTOs);
    TableUpdateRequest.RenameTableRequest req =
        new TableUpdateRequest.RenameTableRequest(expectedTable.name());

    testAlterTable(tableId, req, expectedTable);
  }

  @Test
  public void testUpdateTableComment() throws JsonProcessingException {
    NameIdentifier tableId = NameIdentifier.of(metalakeName, catalogName, "schema1", "table1");
    ColumnDTO[] columns =
        new ColumnDTO[] {createMockColumn("col1", TypeCreator.NULLABLE.I8, "comment1")};

    DistributionDTO distributionDTO = createMockDistributionDTO("col1", 10);
    SortOrderDTO[] sortOrderDTOs = createMockSortOrderDTO("col1", SortOrderDTO.Direction.DESC);

    TableDTO expectedTable =
        createMockTable(
            "table1",
            columns,
            "comment2",
            Collections.emptyMap(),
            new Partition[0],
            distributionDTO,
            sortOrderDTOs);
    TableUpdateRequest.UpdateTableCommentRequest req =
        new TableUpdateRequest.UpdateTableCommentRequest(expectedTable.comment());

    testAlterTable(tableId, req, expectedTable);
  }

  @Test
  public void testSetTableProperty() throws JsonProcessingException {
    NameIdentifier tableId = NameIdentifier.of(metalakeName, catalogName, "schema1", "table1");
    ColumnDTO[] columns =
        new ColumnDTO[] {createMockColumn("col1", TypeCreator.NULLABLE.I8, "comment1")};
    Map<String, String> properties = ImmutableMap.of("k1", "v1");

    DistributionDTO distributionDTO = createMockDistributionDTO("col1", 10);
    SortOrderDTO[] sortOrderDTOs = createMockSortOrderDTO("col1", SortOrderDTO.Direction.DESC);

    TableDTO expectedTable =
        createMockTable(
            "table1",
            columns,
            "comment",
            properties,
            new Partition[0],
            distributionDTO,
            sortOrderDTOs);
    TableUpdateRequest.SetTablePropertyRequest req =
        new TableUpdateRequest.SetTablePropertyRequest("k1", "v1");

    testAlterTable(tableId, req, expectedTable);
  }

  @Test
  public void testRemoveTableProperty() throws JsonProcessingException {
    NameIdentifier tableId = NameIdentifier.of(metalakeName, catalogName, "schema1", "table1");
    ColumnDTO[] columns =
        new ColumnDTO[] {createMockColumn("col1", TypeCreator.NULLABLE.I8, "comment1")};

    DistributionDTO distributionDTO = createMockDistributionDTO("col1", 10);
    SortOrderDTO[] sortOrderDTOs = createMockSortOrderDTO("col1", SortOrderDTO.Direction.DESC);
    TableDTO expectedTable =
        createMockTable(
            "table1",
            columns,
            "comment",
            Collections.emptyMap(),
            new Partition[0],
            distributionDTO,
            sortOrderDTOs);
    TableUpdateRequest.RemoveTablePropertyRequest req =
        new TableUpdateRequest.RemoveTablePropertyRequest("k1");

    testAlterTable(tableId, req, expectedTable);
  }

  @Test
  public void testAddTableColumn() throws JsonProcessingException {
    NameIdentifier tableId = NameIdentifier.of(metalakeName, catalogName, "schema1", "table1");
    ColumnDTO[] columns =
        new ColumnDTO[] {
          createMockColumn("col1", TypeCreator.NULLABLE.I8, "comment1"),
          createMockColumn("col2", TypeCreator.NULLABLE.STRING, "comment2")
        };

    DistributionDTO distributionDTO = createMockDistributionDTO("col2", 10);
    SortOrderDTO[] sortOrderDTOs = createMockSortOrderDTO("col2", SortOrderDTO.Direction.DESC);

    TableDTO expectedTable =
        createMockTable(
            "table1",
            columns,
            "comment",
            Collections.emptyMap(),
            new Partition[0],
            distributionDTO,
            sortOrderDTOs);

    TableUpdateRequest.AddTableColumnRequest req =
        new TableUpdateRequest.AddTableColumnRequest(
            new String[] {"col2"},
            TypeCreator.NULLABLE.STRING,
            "comment2",
            TableChange.ColumnPosition.after("col1"));

    testAlterTable(tableId, req, expectedTable);
  }

  @Test
  public void testRenameTableColumn() throws JsonProcessingException {
    NameIdentifier tableId = NameIdentifier.of(metalakeName, catalogName, "schema1", "table1");
    ColumnDTO[] columns =
        new ColumnDTO[] {
          createMockColumn("col1", TypeCreator.NULLABLE.I8, "comment1"),
          createMockColumn("col2", TypeCreator.NULLABLE.STRING, "comment2"),
          createMockColumn("col3", TypeCreator.NULLABLE.STRING, "comment3")
        };

    DistributionDTO distributionDTO = createMockDistributionDTO("col1", 10);
    SortOrderDTO[] sortOrderDTOs = createMockSortOrderDTO("col3", SortOrderDTO.Direction.DESC);

    TableDTO expectedTable =
        createMockTable(
            "table1",
            columns,
            "comment",
            Collections.emptyMap(),
            new Partition[0],
            distributionDTO,
            sortOrderDTOs);
    TableUpdateRequest.RenameTableColumnRequest req =
        new TableUpdateRequest.RenameTableColumnRequest(new String[] {"col2"}, "col3");

    testAlterTable(tableId, req, expectedTable);
  }

  @Test
  public void testUpdateTableColumnComment() throws JsonProcessingException {
    NameIdentifier tableId = NameIdentifier.of(metalakeName, catalogName, "schema1", "table1");
    ColumnDTO[] columns =
        new ColumnDTO[] {createMockColumn("col1", TypeCreator.NULLABLE.I8, "comment2")};

    DistributionDTO distributionDTO = createMockDistributionDTO("col1", 10);
    SortOrderDTO[] sortOrderDTOs = createMockSortOrderDTO("col1", SortOrderDTO.Direction.DESC);

    TableDTO expectedTable =
        createMockTable(
            "table1",
            columns,
            "comment",
            Collections.emptyMap(),
            new Partition[0],
            distributionDTO,
            sortOrderDTOs);
    TableUpdateRequest.UpdateTableColumnCommentRequest req =
        new TableUpdateRequest.UpdateTableColumnCommentRequest(new String[] {"col1"}, "comment2");

    testAlterTable(tableId, req, expectedTable);
  }

  @Test
  public void testUpdateTableColumnDataType() throws JsonProcessingException {
    NameIdentifier tableId = NameIdentifier.of(metalakeName, catalogName, "schema1", "table1");
    ColumnDTO[] columns =
        new ColumnDTO[] {createMockColumn("col1", TypeCreator.NULLABLE.STRING, "comment1")};

    DistributionDTO distributionDTO = createMockDistributionDTO("col1", 10);
    SortOrderDTO[] sortOrderDTOs = createMockSortOrderDTO("col1", SortOrderDTO.Direction.DESC);
    TableDTO expectedTable =
        createMockTable(
            "table1",
            columns,
            "comment",
            Collections.emptyMap(),
            new Partition[0],
            distributionDTO,
            sortOrderDTOs);
    TableUpdateRequest.UpdateTableColumnTypeRequest req =
        new TableUpdateRequest.UpdateTableColumnTypeRequest(
            new String[] {"col1"}, TypeCreator.NULLABLE.STRING);

    testAlterTable(tableId, req, expectedTable);
  }

  @Test
  public void testUpdateTableColumnPosition() throws JsonProcessingException {
    NameIdentifier tableId = NameIdentifier.of(metalakeName, catalogName, "schema1", "table1");
    ColumnDTO[] columns =
        new ColumnDTO[] {
          createMockColumn("col1", TypeCreator.NULLABLE.I8, "comment1"),
          createMockColumn("col2", TypeCreator.NULLABLE.STRING, "comment2")
        };

    DistributionDTO distributionDTO = createMockDistributionDTO("col1", 10);
    SortOrderDTO[] sortOrderDTOs = createMockSortOrderDTO("col2", SortOrderDTO.Direction.DESC);
    TableDTO expectedTable =
        createMockTable(
            "table1",
            columns,
            "comment",
            Collections.emptyMap(),
            new Partition[0],
            distributionDTO,
            sortOrderDTOs);
    TableUpdateRequest.UpdateTableColumnPositionRequest req =
        new TableUpdateRequest.UpdateTableColumnPositionRequest(
            new String[] {"col1"}, TableChange.ColumnPosition.first());

    testAlterTable(tableId, req, expectedTable);
  }

  private DistributionDTO createMockDistributionDTO(String columnName, int bucketNum) {
    return new DistributionDTO.Builder()
        .withStrategy(Strategy.HASH)
        .withNumber(bucketNum)
        .withExpressions(
            new Expression[] {
              new FieldExpression.Builder().withFieldName(new String[] {columnName}).build()
            })
        .build();
  }

  private SortOrderDTO[] createMockSortOrderDTO(
      String columnName, SortOrderDTO.Direction direction) {
    return new SortOrderDTO[] {
      new SortOrderDTO.Builder()
          .withDirection(direction)
          .withNullOrder(NullOrdering.FIRST)
          .withExpression(
              new FieldExpression.Builder().withFieldName(new String[] {columnName}).build())
          .build()
    };
  }

  @Test
  public void testDeleteTableColumn() throws JsonProcessingException {
    NameIdentifier tableId = NameIdentifier.of(metalakeName, catalogName, "schema1", "table1");
    ColumnDTO[] columns =
        new ColumnDTO[] {createMockColumn("col2", TypeCreator.NULLABLE.STRING, "comment2")};

    DistributionDTO distributionDTO = createMockDistributionDTO("col2", 10);
    SortOrderDTO[] sortOrderDTOs = createMockSortOrderDTO("col2", SortOrderDTO.Direction.DESC);
    TableDTO expectedTable =
        createMockTable(
            "table1",
            columns,
            "comment",
            Collections.emptyMap(),
            new Partition[0],
            distributionDTO,
            sortOrderDTOs);
    TableUpdateRequest.DeleteTableColumnRequest req =
        new TableUpdateRequest.DeleteTableColumnRequest(new String[] {"col1"}, true);

    testAlterTable(tableId, req, expectedTable);
  }

  @Test
  public void testDropTable() throws JsonProcessingException {
    NameIdentifier tableId = NameIdentifier.of(metalakeName, catalogName, "schema1", "table1");
    String tablePath =
        withSlash(
            RelationalCatalog.formatTableRequestPath(tableId.namespace()) + "/" + tableId.name());
    DropResponse resp = new DropResponse(true);
    buildMockResource(Method.DELETE, tablePath, null, resp, SC_OK);

    Assertions.assertTrue(catalog.asTableCatalog().dropTable(tableId));

    // return false
    resp = new DropResponse(false);
    buildMockResource(Method.DELETE, tablePath, null, resp, SC_OK);
    Assertions.assertFalse(catalog.asTableCatalog().dropTable(tableId));

    // Test with exception
    ErrorResponse errorResp = ErrorResponse.internalError("internal error");
    buildMockResource(Method.DELETE, tablePath, null, errorResp, SC_INTERNAL_SERVER_ERROR);

    Assertions.assertFalse(catalog.asTableCatalog().dropTable(tableId));
  }

  private void testAlterTable(NameIdentifier ident, TableUpdateRequest req, TableDTO updatedTable)
      throws JsonProcessingException {
    String tablePath =
        withSlash(RelationalCatalog.formatTableRequestPath(ident.namespace()) + "/" + ident.name());
    TableUpdatesRequest updatesRequest = new TableUpdatesRequest(ImmutableList.of(req));
    TableResponse resp = new TableResponse(updatedTable);
    buildMockResource(Method.PUT, tablePath, updatesRequest, resp, SC_OK);

    Table alteredTable = catalog.asTableCatalog().alterTable(ident, req.tableChange());
    Assertions.assertEquals(updatedTable.name(), alteredTable.name());
    Assertions.assertEquals(updatedTable.comment(), alteredTable.comment());
    Assertions.assertEquals(updatedTable.properties(), alteredTable.properties());

    Assertions.assertEquals(updatedTable.columns().length, alteredTable.columns().length);
    for (int i = 0; i < updatedTable.columns().length; i++) {
      Assertions.assertEquals(updatedTable.columns()[i].name(), alteredTable.columns()[i].name());
      Assertions.assertEquals(
          updatedTable.columns()[i].dataType(), alteredTable.columns()[i].dataType());
      Assertions.assertEquals(
          updatedTable.columns()[i].comment(), alteredTable.columns()[i].comment());
    }

    Assertions.assertArrayEquals(updatedTable.partitioning(), alteredTable.partitioning());
  }

  private void testAlterSchema(
      NameIdentifier ident, SchemaUpdateRequest req, SchemaDTO updatedSchema)
      throws JsonProcessingException {
    String schemaPath =
        withSlash(
            RelationalCatalog.formatSchemaRequestPath(ident.namespace()) + "/" + ident.name());
    SchemaUpdatesRequest updatesReq = new SchemaUpdatesRequest(ImmutableList.of(req));
    SchemaResponse resp = new SchemaResponse(updatedSchema);
    buildMockResource(Method.PUT, schemaPath, updatesReq, resp, SC_OK);

    Schema alteredSchema = catalog.asSchemas().alterSchema(ident, req.schemaChange());
    Assertions.assertEquals(updatedSchema.name(), alteredSchema.name());
    Assertions.assertEquals(updatedSchema.comment(), alteredSchema.comment());
    Assertions.assertEquals(updatedSchema.properties(), alteredSchema.properties());
  }

  private static String withSlash(String path) {
    return "/" + path;
  }

  private static SchemaDTO createMockSchema(
      String name, String comment, Map<String, String> props) {
    return new SchemaDTO.Builder()
        .withName(name)
        .withComment(comment)
        .withProperties(props)
        .withAudit(
            new AuditDTO.Builder().withCreator("creator").withCreateTime(Instant.now()).build())
        .build();
  }

  private static ColumnDTO createMockColumn(String name, Type type, String comment) {
    return new ColumnDTO.Builder().withName(name).withDataType(type).withComment(comment).build();
  }

  private static TableDTO createMockTable(
      String name, ColumnDTO[] columns, String comment, Map<String, String> properties) {
    return createMockTable(name, columns, comment, properties, new Partition[0], null, null);
  }

  private static TableDTO createMockTable(
      String name,
      ColumnDTO[] columns,
      String comment,
      Map<String, String> properties,
      Partition[] partitions,
      DistributionDTO distributionDTO,
      SortOrderDTO[] sortOrderDTOs) {
    return new TableDTO.Builder()
        .withName(name)
        .withColumns(columns)
        .withComment(comment)
        .withProperties(properties)
        .withDistribution(distributionDTO)
        .withSortOrders(sortOrderDTOs)
        .withAudit(
            new AuditDTO.Builder().withCreator("creator").withCreateTime(Instant.now()).build())
        .withPartitions(partitions)
        .build();
  }
}