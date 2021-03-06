// AUTOGENERATED FILE - DO NOT MODIFY!
// This file generated by Djinni from inttypes.djinni

#import "DBAssortedIntegers.h"


@implementation DBAssortedIntegers

- (id)initWithAssortedIntegers:(DBAssortedIntegers *)assortedIntegers
{
    if (self = [super init]) {
        _eight = assortedIntegers.eight;
        _sixteen = assortedIntegers.sixteen;
        _thirtytwo = assortedIntegers.thirtytwo;
        _sixtyfour = assortedIntegers.sixtyfour;
        if (assortedIntegers.oEight == nil) {
            _oEight = nil;
        } else {
            _oEight = assortedIntegers.oEight;
        }
        if (assortedIntegers.oSixteen == nil) {
            _oSixteen = nil;
        } else {
            _oSixteen = assortedIntegers.oSixteen;
        }
        if (assortedIntegers.oThirtytwo == nil) {
            _oThirtytwo = nil;
        } else {
            _oThirtytwo = assortedIntegers.oThirtytwo;
        }
        if (assortedIntegers.oSixtyfour == nil) {
            _oSixtyfour = nil;
        } else {
            _oSixtyfour = assortedIntegers.oSixtyfour;
        }
    }
    return self;
}

- (id)initWithEight:(int8_t)eight
            sixteen:(int16_t)sixteen
          thirtytwo:(int32_t)thirtytwo
          sixtyfour:(int64_t)sixtyfour
             oEight:(nullable NSNumber *)oEight
           oSixteen:(nullable NSNumber *)oSixteen
         oThirtytwo:(nullable NSNumber *)oThirtytwo
         oSixtyfour:(nullable NSNumber *)oSixtyfour
{
    if (self = [super init]) {
        _eight = eight;
        _sixteen = sixteen;
        _thirtytwo = thirtytwo;
        _sixtyfour = sixtyfour;
        _oEight = oEight;
        _oSixteen = oSixteen;
        _oThirtytwo = oThirtytwo;
        _oSixtyfour = oSixtyfour;
    }
    return self;
}

- (BOOL)isEqual:(id)other
{
    if (![other isKindOfClass:[DBAssortedIntegers class]]) {
        return NO;
    }
    DBAssortedIntegers *typedOther = (DBAssortedIntegers *)other;
    return self.eight == typedOther.eight &&
            self.sixteen == typedOther.sixteen &&
            self.thirtytwo == typedOther.thirtytwo &&
            self.sixtyfour == typedOther.sixtyfour &&
            ((self.oEight == nil && typedOther.oEight == nil) || (self.oEight != nil && [self.oEight isEqual:typedOther.oEight])) &&
            ((self.oSixteen == nil && typedOther.oSixteen == nil) || (self.oSixteen != nil && [self.oSixteen isEqual:typedOther.oSixteen])) &&
            ((self.oThirtytwo == nil && typedOther.oThirtytwo == nil) || (self.oThirtytwo != nil && [self.oThirtytwo isEqual:typedOther.oThirtytwo])) &&
            ((self.oSixtyfour == nil && typedOther.oSixtyfour == nil) || (self.oSixtyfour != nil && [self.oSixtyfour isEqual:typedOther.oSixtyfour]));
}

@end
