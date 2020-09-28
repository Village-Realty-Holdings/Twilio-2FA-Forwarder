package dev.obx.Twilio2FAForwarder.Config;

import java.util.function.Predicate;

public class TwoFAPhoneNumber implements Predicate<TwoFAPhoneNumber> {
	public String name;
	public String phoneNumber;
	public int groupId;
	
    public boolean test(TwoFAPhoneNumber input) {
        
        if(phoneNumber.equals(input.phoneNumber)) {
            return true;
        }
        return false;
    }
}
