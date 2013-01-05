package com.facebook.presto.metadata;

import com.facebook.presto.operator.aggregation.AggregationFunction;
import com.facebook.presto.operator.scalar.MathFunctions;
import com.facebook.presto.operator.scalar.ScalarFunction;
import com.facebook.presto.operator.scalar.StringFunctions;
import com.facebook.presto.slice.Slice;
import com.facebook.presto.sql.tree.QualifiedName;
import com.facebook.presto.tuple.TupleInfo;
import com.facebook.presto.tuple.TupleInfo.Type;
import com.google.common.base.Joiner;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.facebook.presto.operator.aggregation.CountAggregation.COUNT;
import static com.facebook.presto.operator.aggregation.DoubleAverageAggregation.DOUBLE_AVERAGE;
import static com.facebook.presto.operator.aggregation.DoubleMaxAggregation.DOUBLE_MAX;
import static com.facebook.presto.operator.aggregation.DoubleMinAggregation.DOUBLE_MIN;
import static com.facebook.presto.operator.aggregation.DoubleSumAggregation.DOUBLE_SUM;
import static com.facebook.presto.operator.aggregation.LongAverageAggregation.LONG_AVERAGE;
import static com.facebook.presto.operator.aggregation.LongMaxAggregation.LONG_MAX;
import static com.facebook.presto.operator.aggregation.LongMinAggregation.LONG_MIN;
import static com.facebook.presto.operator.aggregation.LongSumAggregation.LONG_SUM;
import static com.facebook.presto.operator.aggregation.VarBinaryMaxAggregation.VAR_BINARY_MAX;
import static com.facebook.presto.operator.aggregation.VarBinaryMinAggregation.VAR_BINARY_MIN;
import static com.facebook.presto.tuple.TupleInfo.Type.DOUBLE;
import static com.facebook.presto.tuple.TupleInfo.Type.FIXED_INT_64;
import static com.facebook.presto.tuple.TupleInfo.Type.VARIABLE_BINARY;
import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.String.format;
import static java.lang.invoke.MethodHandles.lookup;

public class FunctionRegistry
{
    private final Multimap<QualifiedName, FunctionInfo> functionsByName;
    private final Map<FunctionHandle, FunctionInfo> functionsByHandle;

    public FunctionRegistry()
    {
        List<FunctionInfo> functions = new FunctionListBuilder()
                .aggregate("count", FIXED_INT_64, ImmutableList.<TupleInfo.Type>of(), FIXED_INT_64, COUNT)
                .aggregate("sum", FIXED_INT_64, ImmutableList.of(FIXED_INT_64), FIXED_INT_64, LONG_SUM)
                .aggregate("sum", DOUBLE, ImmutableList.of(DOUBLE), DOUBLE, DOUBLE_SUM)
                .aggregate("avg", DOUBLE, ImmutableList.of(DOUBLE), VARIABLE_BINARY, DOUBLE_AVERAGE)
                .aggregate("avg", DOUBLE, ImmutableList.of(FIXED_INT_64), VARIABLE_BINARY, LONG_AVERAGE)
                .aggregate("max", FIXED_INT_64, ImmutableList.of(FIXED_INT_64), FIXED_INT_64, LONG_MAX)
                .aggregate("max", DOUBLE, ImmutableList.of(DOUBLE), DOUBLE, DOUBLE_MAX)
                .aggregate("max", VARIABLE_BINARY, ImmutableList.of(VARIABLE_BINARY), VARIABLE_BINARY, VAR_BINARY_MAX)
                .aggregate("min", FIXED_INT_64, ImmutableList.of(FIXED_INT_64), FIXED_INT_64, LONG_MIN)
                .aggregate("min", DOUBLE, ImmutableList.of(DOUBLE), DOUBLE, DOUBLE_MIN)
                .aggregate("min", VARIABLE_BINARY, ImmutableList.of(VARIABLE_BINARY), VARIABLE_BINARY, VAR_BINARY_MIN)
                .scalar(StringFunctions.class)
                .scalar(MathFunctions.class)
                .build();

        functionsByName = Multimaps.index(functions, FunctionInfo.nameGetter());
        functionsByHandle = Maps.uniqueIndex(functions, FunctionInfo.handleGetter());
    }

    public FunctionInfo get(QualifiedName name, List<TupleInfo.Type> parameterTypes)
    {
        // search for exact match
        for (FunctionInfo functionInfo : functionsByName.get(name)) {
            if (functionInfo.getArgumentTypes().equals(parameterTypes)) {
                return functionInfo;
            }
        }

        // search for coerced match
        for (FunctionInfo functionInfo : functionsByName.get(name)) {
            if (canCoerce(parameterTypes, functionInfo)) {
                return functionInfo;
            }
        }

        String parameters = Joiner.on(", ").useForNull("NULL").join(parameterTypes);
        throw new IllegalArgumentException(format("Function %s(%s) not registered", name, parameters));
    }

    private boolean canCoerce(List<Type> parameterTypes, FunctionInfo functionInfo)
    {
        List<Type> functionArguments = functionInfo.getArgumentTypes();
        if (parameterTypes.size() != functionArguments.size()) {
            return false;
        }
        for (int i = 0; i < functionArguments.size(); i++) {
            Type functionArgument = functionArguments.get(i);
            Type parameterType = parameterTypes.get(i);
            if (functionArgument != parameterType && !(functionArgument == DOUBLE && parameterType == FIXED_INT_64)) {
                return false;
            }
        }
        return true;
    }

    public FunctionInfo get(FunctionHandle handle)
    {
        return functionsByHandle.get(handle);
    }

    private static List<TupleInfo.Type> types(MethodHandle handle)
    {
        ImmutableList.Builder<TupleInfo.Type> types = ImmutableList.builder();
        for (Class<?> parameter : handle.type().parameterList()) {
            types.add(type(parameter));
        }
        return types.build();
    }

    private static TupleInfo.Type type(Class<?> clazz)
    {
        if (clazz == long.class) {
            return FIXED_INT_64;
        }
        if (clazz == double.class) {
            return DOUBLE;
        }
        if (clazz == Slice.class) {
            return VARIABLE_BINARY;
        }
        throw new IllegalArgumentException("Unhandled type: " + clazz.getName());
    }

    private static class FunctionListBuilder
    {
        private final List<FunctionInfo> functions = new ArrayList<>();

        public FunctionListBuilder aggregate(String name, TupleInfo.Type returnType, List<TupleInfo.Type> argumentTypes, TupleInfo.Type intermediateType, AggregationFunction function)
        {
            name = name.toLowerCase();

            int id = functions.size() + 1;
            functions.add(new FunctionInfo(id, QualifiedName.of(name), returnType, argumentTypes, intermediateType, function));
            return this;
        }

        public FunctionListBuilder scalar(String name, MethodHandle function)
        {
            name = name.toLowerCase();

            int id = functions.size() + 1;
            TupleInfo.Type returnType = type(function.type().returnType());
            List<TupleInfo.Type> argumentTypes = types(function);
            functions.add(new FunctionInfo(id, QualifiedName.of(name), returnType, argumentTypes, function));
            return this;
        }

        public FunctionListBuilder scalar(Class<?> clazz)
        {
            try {
                boolean foundOne = false;
                for (Method method : clazz.getMethods()) {
                    ScalarFunction scalarFunction = method.getAnnotation(ScalarFunction.class);
                    if (scalarFunction == null) {
                        continue;
                    }
                    checkArgument(isValidMethod(method), "@ScalarFunction method %s is not valid", method);
                    MethodHandle methodHandle = lookup().unreflect(method);
                    String name = scalarFunction.value();
                    if (name.isEmpty()) {
                        name = method.getName();
                    }
                    scalar(name, methodHandle);
                    for (String alias : scalarFunction.alias()) {
                        scalar(alias, methodHandle);
                    }
                    foundOne = true;
                }
                checkArgument(foundOne, "Expected class %s to contain at least one method annotated with @%s", clazz.getName(), ScalarFunction.class.getSimpleName());
            }
            catch (Exception e) {
                throw Throwables.propagate(e);
            }
            return this;
        }

        private static final Set<Class<?>> SUPPORTED_TYPES = ImmutableSet.<Class<?>>of(long.class, double.class, Slice.class);

        private boolean isValidMethod(Method method)
        {
            if (!Modifier.isStatic(method.getModifiers())) {
                return false;
            }
            if (! SUPPORTED_TYPES.contains(method.getReturnType())) {
                return false;
            }
            for (Class<?> type : method.getParameterTypes()) {
                if (! SUPPORTED_TYPES.contains(type)) {
                    return false;
                }
            }
            return true;
        }

        public ImmutableList<FunctionInfo> build()
        {
            Collections.sort(functions);
            return ImmutableList.copyOf(functions);
        }
    }
}
