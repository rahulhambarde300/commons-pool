package org.apache.commons.pool3;


public class FailureSimulator {
    private boolean makeObjectFail;
    private boolean activateObjectFail;
    private boolean validateObjectFail;
    private boolean passivateObjectFail;
    private boolean destroyObjectFail;

    public boolean isActivateObjectFail() {
        return activateObjectFail;
    }

    public boolean isDestroyObjectFail() {
        return destroyObjectFail;
    }

    public boolean isMakeObjectFail() {
        return makeObjectFail;
    }

    public boolean isPassivateObjectFail() {
        return passivateObjectFail;
    }

    public boolean isValidateObjectFail() {
        return validateObjectFail;
    }

    public void setActivateObjectFail(final boolean activateObjectFail) {
        this.activateObjectFail = activateObjectFail;
    }

    public void setDestroyObjectFail(final boolean destroyObjectFail) {
        this.destroyObjectFail = destroyObjectFail;
    }

    public void setMakeObjectFail(final boolean makeObjectFail) {
        this.makeObjectFail = makeObjectFail;
    }

    public void setPassivateObjectFail(final boolean passivateObjectFail) {
        this.passivateObjectFail = passivateObjectFail;
    }

    public void setValidateObjectFail(final boolean validateObjectFail) {
        this.validateObjectFail = validateObjectFail;
    }

    public void reset() {
        setMakeObjectFail(false);
        setActivateObjectFail(false);
        setValidateObjectFail(false);
        setPassivateObjectFail(false);
        setDestroyObjectFail(false);
    }
}
