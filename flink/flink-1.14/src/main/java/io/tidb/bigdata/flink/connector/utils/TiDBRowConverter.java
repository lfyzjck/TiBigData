package io.tidb.bigdata.flink.connector.utils;

import static java.lang.String.format;

import java.io.Serializable;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.Schema;
import org.apache.flink.table.api.Schema.Builder;
import org.apache.flink.table.data.RowData;
import org.tikv.common.exception.TiBatchWriteException;
import org.tikv.common.meta.TiColumnInfo;
import org.tikv.common.meta.TiTableInfo;
import org.tikv.common.row.ObjectRowImpl;
import org.tikv.common.row.Row;
import org.tikv.common.types.DataType;
import org.tikv.common.types.StringType;

public class TiDBRowConverter implements Serializable {

  private final TiTableInfo tiTableInfo;
  private final List<TiColumnInfo> columns;

  public TiDBRowConverter(TiTableInfo tiTableInfo) {
    this.tiTableInfo = tiTableInfo;
    this.columns = tiTableInfo.getColumns();
  }

  /**
   * TiKV DataType -> Flink DataType
   *
   * @param dataType TiKV DataType
   * @return Flink DataType
   */
  public static org.apache.flink.table.types.DataType toFlinkType(
      org.tikv.common.types.DataType dataType) {
    boolean notNull = dataType.isNotNull();
    boolean unsigned = dataType.isUnsigned();
    int length = (int) dataType.getLength();
    org.apache.flink.table.types.DataType flinkType;
    switch (dataType.getType()) {
      case TypeBit:
        flinkType = DataTypes.BOOLEAN();
        break;
      case TypeTiny:
        flinkType = unsigned ? DataTypes.SMALLINT() : DataTypes.TINYINT();
        break;
      case TypeYear:
      case TypeShort:
        flinkType = unsigned ? DataTypes.INT() : DataTypes.SMALLINT();
        break;
      case TypeInt24:
      case TypeLong:
        flinkType = unsigned ? DataTypes.BIGINT() : DataTypes.INT();
        break;
      case TypeLonglong:
        flinkType = unsigned ? DataTypes.DECIMAL(length, 0) : DataTypes.BIGINT();
        break;
      case TypeFloat:
        flinkType = DataTypes.FLOAT();
        break;
      case TypeDouble:
        flinkType = DataTypes.DOUBLE();
        break;
      case TypeNull:
        flinkType = DataTypes.NULL();
        break;
      case TypeDatetime:
      case TypeTimestamp:
        flinkType = DataTypes.TIMESTAMP();
        break;
      case TypeDate:
      case TypeNewDate:
        flinkType = DataTypes.DATE();
        break;
      case TypeDuration:
        flinkType = DataTypes.TIME();
        break;
      case TypeTinyBlob:
      case TypeMediumBlob:
      case TypeLongBlob:
      case TypeBlob:
      case TypeVarString:
      case TypeString:
      case TypeVarchar:
        if (dataType instanceof StringType) {
          flinkType = DataTypes.STRING();
        } else {
          flinkType = DataTypes.BYTES();
        }
        break;
      case TypeJSON:
      case TypeEnum:
      case TypeSet:
        flinkType = DataTypes.STRING();
        break;
      case TypeDecimal:
      case TypeNewDecimal:
        flinkType = DataTypes.DECIMAL(length, dataType.getDecimal());
        break;
      case TypeGeometry:
      default:
        throw new IllegalArgumentException(
            format("can not get flink datatype by tikv type: %s", dataType));
    }
    return notNull ? flinkType.notNull() : flinkType.nullable();
  }

  public Schema getSchema() {
    Builder builder = Schema.newBuilder();
    columns.forEach(column -> builder.column(column.getName(), toFlinkType(column.getType())));
    return builder.build();
  }


  private void setDefaultValue(Row row, int pos, DataType type) {
    // TODO set default value
    row.set(pos, type, null);
  }

  public Row toTiRow(RowData rowData, boolean ignoreAutoincrementColumn) {
    int arity = rowData.getArity();
    Row tiRow = ObjectRowImpl.create(arity);
    for (int i = 0; i < arity; i++) {
      TiColumnInfo tiColumnInfo = columns.get(i);
      DataType type = tiColumnInfo.getType();
      if (rowData.isNullAt(i)) {
        tiRow.setNull(i);
        // check not null
        if (type.isNotNull()) {
          if (type.isAutoIncrement()) {
            if (!ignoreAutoincrementColumn) {
              throw new IllegalStateException(
                  String.format("Auto increment column: %s can not be null",
                      tiColumnInfo.getName()));
            } else {
              continue;
            }
          }
          throw new IllegalStateException(
              String.format("Column: %s can not be null", tiColumnInfo.getName()));
        }
        continue;
      }
      boolean unsigned = type.isUnsigned();
      int length = (int) type.getLength();
      switch (type.getType()) {
        case TypeNull:
          break;
        case TypeBit:
        case TypeTiny:
        case TypeShort:
        case TypeInt24:
        case TypeLong:
        case TypeYear:
          tiRow.set(i, type, rowData.getLong(i));
          break;
        case TypeDatetime:
        case TypeTimestamp:
          tiRow.set(i, type, rowData.getTimestamp(i, 6).toTimestamp());
          break;
        case TypeNewDate:
        case TypeDate:
          tiRow.set(i, type, Date.valueOf(LocalDate.ofEpochDay(rowData.getInt(i))));
          break;
        case TypeDuration:
          tiRow.set(i, type, 1000L * 1000L * rowData.getInt(i));
          break;
        case TypeLonglong:
          if (unsigned) {
            tiRow.set(i, type, rowData.getDecimal(i, length, 0).toBigDecimal());
          } else {
            tiRow.set(i, type, rowData.getLong(i));
          }
          break;
        case TypeFloat:
          tiRow.set(i, type, rowData.getFloat(i));
          break;
        case TypeDouble:
          tiRow.set(i, type, rowData.getDouble(i));
          break;
        case TypeTinyBlob:
        case TypeMediumBlob:
        case TypeLongBlob:
        case TypeBlob:
        case TypeVarString:
        case TypeString:
        case TypeVarchar:
          if (type instanceof StringType) {
            tiRow.set(i, type, rowData.getString(i).toString());
          } else {
            tiRow.set(i, type, rowData.getBinary(i));
          }
          break;
        case TypeSet:
        case TypeJSON:
          // since tikv java client is not supported json/set encoding
          throw new TiBatchWriteException("JSON/SET encoding is not supported now.");
        case TypeEnum:
          tiRow.set(i, type, rowData.getString(i).toString());
          break;
        case TypeDecimal:
        case TypeNewDecimal:
          tiRow.set(i, type, rowData.getDecimal(i, length, type.getDecimal()).toBigDecimal());
          break;
        case TypeGeometry:
        default:
          throw new IllegalArgumentException(String.format("Unsupported type: %s", type));
      }
    }
    return tiRow;
  }


}