package org.apache.commons.pool3;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.pool3.impl.DefaultPooledObject;

/**
 * A poolable object factory that tracks how methods are called.
 */
public class MethodCallPoolableObjectFactory implements PooledObjectFactory<Object, PrivateException> {
    private final List<MethodCall> methodCalls = new ArrayList<>();
    private int count;
    private boolean valid = true;
    private FailureSimulator failureSimulator = new FailureSimulator();

    @Override
    public void activateObject(final PooledObject<Object> obj) {
        methodCalls.add(new MethodCall("activateObject", obj.getObject()));
        if (failureSimulator.isActivateObjectFail()) {
            throw new PrivateException("activateObject");
        }
    }

    @Override
    public void destroyObject(final PooledObject<Object> obj) {
        methodCalls.add(new MethodCall("destroyObject", obj.getObject()));
        if (failureSimulator.isDestroyObjectFail()) {
            throw new PrivateException("destroyObject");
        }
    }

    public int getCurrentCount() {
        return count;
    }

    public List<MethodCall> getMethodCalls() {
        return methodCalls;
    }

    public boolean isValid() {
        return valid;
    }

    public void reset() {
        count = 0;
        getMethodCalls().clear();
        failureSimulator.reset();
        setValid(true);
    }

    public void setValid(final boolean valid) {
        this.valid = valid;
    }

    @Override
    public PooledObject<Object> makeObject() {
        final MethodCall call = new MethodCall("makeObject");
        methodCalls.add(call);
        final int originalCount = this.count++;
        if (failureSimulator.isMakeObjectFail()) {
            throw new PrivateException("makeObject");
        }
        final Integer obj = Integer.valueOf(originalCount);
        call.setReturned(obj);
        return new DefaultPooledObject<>(obj);
    }

    @Override
    public void passivateObject(final PooledObject<Object> obj) {
        methodCalls.add(new MethodCall("passivateObject", obj.getObject()));
        if (failureSimulator.isPassivateObjectFail()) {
            throw new PrivateException("passivateObject");
        }
    }

    @Override
    public boolean validateObject(final PooledObject<Object> obj) {
        final MethodCall call = new MethodCall("validateObject", obj.getObject());
        methodCalls.add(call);
        if (failureSimulator.isValidateObjectFail()) {
            throw new PrivateException("validateObject");
        }
        final boolean r = valid;
        call.returned(Boolean.valueOf(r));
        return r;
    }

    // Getters and setters for failureSimulator's properties are handled through failureSimulator directly
    public FailureSimulator getFailureSimulator() {
        return failureSimulator;
    }
}
